/**
 * Copyright 2016-2017 Seznam.cz, a.s.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package cz.seznam.euphoria.flink.batch;

import cz.seznam.euphoria.core.client.dataset.windowing.Window;
import cz.seznam.euphoria.core.client.dataset.windowing.Windowing;
import cz.seznam.euphoria.core.client.functional.ResultType;
import cz.seznam.euphoria.core.client.functional.UnaryFunction;
import cz.seznam.euphoria.core.client.operator.ExtractEventTime;
import cz.seznam.euphoria.core.client.operator.ReduceStateByKey;
import cz.seznam.euphoria.core.client.operator.state.State;
import cz.seznam.euphoria.core.client.operator.state.StateFactory;
import cz.seznam.euphoria.core.client.operator.state.StateMerger;
import cz.seznam.euphoria.core.client.operator.state.StorageProvider;
import cz.seznam.euphoria.core.client.triggers.Trigger;
import cz.seznam.euphoria.core.client.util.Pair;
import cz.seznam.euphoria.core.executor.greduce.GroupReducer;
import cz.seznam.euphoria.core.util.Settings;
import cz.seznam.euphoria.flink.FlinkOperator;
import cz.seznam.euphoria.flink.Utils;
import cz.seznam.euphoria.flink.functions.PartitionerWrapper;
import cz.seznam.euphoria.flink.types.TypeSupport;
import cz.seznam.euphoria.shaded.guava.com.google.common.collect.Iterables;
import org.apache.flink.api.common.functions.GroupReduceFunction;
import org.apache.flink.api.common.operators.Order;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.DataSet;
import org.apache.flink.api.java.ExecutionEnvironment;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.api.java.typeutils.ResultTypeQueryable;

public class ReduceStateByKeyTranslator implements BatchOperatorTranslator<ReduceStateByKey> {

  final StorageProvider stateStorageProvider;

  public ReduceStateByKeyTranslator(Settings settings, ExecutionEnvironment env) {
    int maxMemoryElements = settings.getInt(CFG_MAX_MEMORY_ELEMENTS_KEY, CFG_MAX_MEMORY_ELEMENTS_DEFAULT);
    this.stateStorageProvider = new BatchStateStorageProvider(maxMemoryElements, env);
  }

  @Override
  @SuppressWarnings("unchecked")
  public DataSet translate(FlinkOperator<ReduceStateByKey> operator,
                           BatchExecutorContext context) {

    final int inputParallelism =
        Iterables.getOnlyElement(context.getInputOperators(operator)).getParallelism();
    final DataSet input =
        Iterables.getOnlyElement(context.getInputStreams(operator));

    final ReduceStateByKey origOperator = operator.getOriginalOperator();

    final Windowing windowing;
    TypeInformation windowType;
    if (origOperator.getWindowing() == null) {
      windowing = AttachedWindowing.INSTANCE;
      windowType = TypeSupport.extractWindowType(input);
    } else {
      windowing = origOperator.getWindowing();
      windowType = windowing.getWindowType() == null
          ? null
          : TypeSupport.toTypeInfo(windowing.getWindowType());
    }
    if (windowType == null) {
      windowType = TypeInformation.of(Window.class);
    }

    TypeSupport.FunctionMeta<UnaryFunction> udfKeyMeta =
        TypeSupport.FunctionMeta.of(origOperator.getKeyExtractor());
    TypeSupport.FunctionMeta<UnaryFunction> udfValMeta =
        TypeSupport.FunctionMeta.of(origOperator.getValueExtractor());

    // ~ extract key/value + timestamp from input elements and assign windows
    DataSet<BatchElement<?, Pair>> wAssigned;
    {
      // FIXME require keyExtractor to deliver `Comparable`s
      UnaryFunction udfKey = udfKeyMeta.function;
      UnaryFunction udfVal = udfValMeta.function;
      ExtractEventTime timeAssigner = origOperator.getEventTimeAssigner();
      wAssigned =
          input.flatMap((i, c) -> {
            BatchElement wel = (BatchElement) i;

            // assign timestamp if timeAssigner defined
            if (timeAssigner != null) {
              wel.setTimestamp(timeAssigner.extractTimestamp(wel.getElement()));
            }
            Iterable<Window> assigned = windowing.assignWindowsToElement(wel);
            for (Window wid : assigned) {
              Object el = wel.getElement();
              c.collect(new BatchElement<>(wid,
                  wel.getTimestamp(),
                  Pair.of(udfKey.apply(el), udfVal.apply(el))));
            }
          })
          .returns((TypeInformation) TypeSupport.forBatchElement(
              windowType,
              TypeSupport.forPair(
                  TypeSupport.toTypeInfo(udfKeyMeta.rtype, Comparable.class),
                  TypeSupport.toTypeInfo(udfValMeta.rtype))))
          .name(operator.getName() + "::map-input")
          .setParallelism(inputParallelism);
    }

    // ~ reduce the data now
    DataSet<BatchElement<?, Pair>> reduced =
        // ~ FIXME if the underlying windowing is "non merging" we can group by
        // "key _and_ window", thus, better utilizing the available resources
        wAssigned.groupBy("element.first")
            .sortGroup("timestamp", Order.ASCENDING)
            .reduceGroup(new RSBKReducer(origOperator, stateStorageProvider,
                windowing, windowType, udfKeyMeta.rtype, udfValMeta.rtype))
            .setParallelism(operator.getParallelism())
            .name(operator.getName() + "::reduce");

    // apply custom partitioner if different from default
    if (!origOperator.getPartitioning().hasDefaultPartitioner()) {
      reduced = reduced
          .partitionCustom(new PartitionerWrapper<>(
              origOperator.getPartitioning().getPartitioner()),
              Utils.wrapQueryable(
                  (KeySelector<BatchElement<?, Pair>, Comparable>)
                      (BatchElement<?, Pair> we) -> (Comparable) we.getElement().getKey(),
                  Comparable.class))
          .setParallelism(operator.getParallelism());
    }

    return reduced;
  }

  static class RSBKReducer
          implements GroupReduceFunction<BatchElement<?, Pair>, BatchElement<?, Pair>>,
          ResultTypeQueryable<BatchElement<?, Pair>>
  {
    private final TypeInformation windowType;
    private final ResultType resultKeyType;
    private final ResultType resultValueType;

    private final StateFactory<?, ?, State<?, ?>> stateFactory;
    private final StateMerger<?, ?, State<?, ?>> stateCombiner;
    private final StorageProvider stateStorageProvider;
    private final Windowing windowing;
    private final Trigger trigger;

    @SuppressWarnings("unchecked")
    RSBKReducer(
        ReduceStateByKey operator,
        StorageProvider stateStorageProvider,
        Windowing windowing,
        TypeInformation windowType,
        ResultType resultKeyType,
        ResultType resultValueType) {

      this.stateFactory = operator.getStateFactory();
      this.stateCombiner = operator.getStateMerger();
      this.stateStorageProvider = stateStorageProvider;
      this.windowing = windowing;
      this.trigger = windowing.getTrigger();

      this.windowType = windowType;
      this.resultKeyType = resultKeyType;
      this.resultValueType = resultValueType;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void reduce(Iterable<BatchElement<?, Pair>> values,
                       org.apache.flink.util.Collector<BatchElement<?, Pair>> out)
    {
      GroupReducer reducer = new GroupReducer(
              stateFactory,
              stateCombiner,
              stateStorageProvider,
              BatchElement::new,
              windowing,
              trigger,
              elem -> out.collect((BatchElement) elem));
      for (BatchElement value : values) {
        reducer.process(value);
      }
      reducer.close();
    }

    @Override
    @SuppressWarnings("unchecked")
    public TypeInformation<BatchElement<?, Pair>> getProducedType() {
      return (TypeInformation) TypeSupport.forBatchElement(windowType,
          TypeSupport.forPair(resultKeyType, resultValueType));
    }
  }
}
