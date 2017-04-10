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

import cz.seznam.euphoria.core.client.dataset.windowing.MergingWindowing;
import cz.seznam.euphoria.core.client.dataset.windowing.TimedWindow;
import cz.seznam.euphoria.core.client.dataset.windowing.Window;
import cz.seznam.euphoria.core.client.dataset.windowing.Windowing;
import cz.seznam.euphoria.core.client.functional.UnaryFunction;
import cz.seznam.euphoria.core.client.operator.ExtractEventTime;
import cz.seznam.euphoria.core.client.operator.ReduceByKey;
import cz.seznam.euphoria.core.client.util.Pair;
import cz.seznam.euphoria.flink.FlinkOperator;
import cz.seznam.euphoria.flink.Utils;
import cz.seznam.euphoria.flink.functions.PartitionerWrapper;
import cz.seznam.euphoria.flink.types.TypeSupport;
import cz.seznam.euphoria.shaded.guava.com.google.common.base.Preconditions;
import cz.seznam.euphoria.shaded.guava.com.google.common.collect.Iterables;
import org.apache.flink.api.common.functions.ReduceFunction;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.DataSet;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.api.java.operators.FlatMapOperator;
import org.apache.flink.api.java.operators.Operator;

import java.util.Arrays;

public class ReduceByKeyTranslator implements BatchOperatorTranslator<ReduceByKey> {

  static boolean wantTranslate(ReduceByKey operator) {
    boolean b = operator.isCombinable()
        && (operator.getWindowing() == null
            || (!(operator.getWindowing() instanceof MergingWindowing)
                && !operator.getWindowing().getTrigger().isStateful()));
    return b;
  }

  @Override
  @SuppressWarnings("unchecked")
  public DataSet translate(FlinkOperator<ReduceByKey> operator,
                           BatchExecutorContext context) {

    int inputParallelism =
            Iterables.getOnlyElement(context.getInputOperators(operator)).getParallelism();
    final DataSet input =
            Iterables.getOnlyElement(context.getInputStreams(operator));

    final ReduceByKey origOperator = operator.getOriginalOperator();
    final UnaryFunction<Iterable, Object> reducer = origOperator.getReducer();

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
      // ~ we just assume/hope that what ever windows will be produced
      // are `Comparable` since that is what the `.groupBy` operator requires
      windowType = TypeInformation.of(Comparable.class);
    }

    Preconditions.checkState(origOperator.isCombinable(),
        "Non-combinable ReduceByKey not supported!");
    Preconditions.checkState(
        !(windowing instanceof MergingWindowing),
        "MergingWindowing not supported!");
    Preconditions.checkState(!windowing.getTrigger().isStateful(),
        "Stateful triggers not supported!");

    // ~ prepare key and value functions
    TypeSupport.FunctionMeta<UnaryFunction> udfKeyMeta =
        TypeSupport.FunctionMeta.of(origOperator.getKeyExtractor());
    TypeSupport.FunctionMeta<UnaryFunction> udfValMeta =
        TypeSupport.FunctionMeta.of(origOperator.getValueExtractor());

    // ~ extract key/value from input elements and assign windows
    DataSet<BatchElement<Window, Pair>> tuples;
    {
      // FIXME require keyExtractor to deliver `Comparable`s
      // FIXME require windows to be `Comparable`

      UnaryFunction udfKey = udfKeyMeta.function;
      UnaryFunction udfVal = udfValMeta.function;
      ExtractEventTime timeAssigner = origOperator.getEventTimeAssigner();
      FlatMapOperator<Object, BatchElement<Window, Pair>> wAssigned =
          input.flatMap((i, c) -> {
            BatchElement wel = (BatchElement) i;
            if (timeAssigner != null) {
              long stamp = timeAssigner.extractTimestamp(wel.getElement());
              wel.setTimestamp(stamp);
            }
            Iterable<Window> assigned = windowing.assignWindowsToElement(wel);
            for (Window wid : assigned) {
              Object el = wel.getElement();
              long stamp = (wid instanceof TimedWindow)
                  ? ((TimedWindow) wid).maxTimestamp()
                  : wel.getTimestamp();
              c.collect(new BatchElement<>(
                      wid, stamp, Pair.of(udfKey.apply(el), udfVal.apply(el))));
            }
          });
      tuples = wAssigned.name(operator.getName() + "::map-input")
                   .setParallelism(inputParallelism)
                   .returns((TypeInformation) TypeSupport.forBatchElement(
                       windowType,
                       TypeSupport.forPair(
                           TypeSupport.toTypeInfo(udfKeyMeta.rtype, Comparable.class),
                           TypeSupport.toTypeInfo(udfValMeta.rtype))));
    }

    // ~ reduce the data now
    Operator<BatchElement<Window, Pair>, ?> reduced =
            tuples.groupBy("window", "element.first")
                    .reduce(new RBKReducer(reducer))
                    .setParallelism(operator.getParallelism())
                    .name(operator.getName() + "::reduce");

    // FIXME partitioner should be applied during "reduce" to avoid
    // unnecessary shuffle, but there is no (known) way how to set custom
    // partitioner to "groupBy" transformation

    // apply custom partitioner if different from default
    if (!origOperator.getPartitioning().hasDefaultPartitioner()) {
      reduced = reduced
          .partitionCustom(
              new PartitionerWrapper<>(origOperator.getPartitioning().getPartitioner()),
              Utils.wrapQueryable(
                  (KeySelector<BatchElement<Window, Pair>, Comparable>)
                      (BatchElement<Window, Pair> we) -> (Comparable) we.getElement().getKey(),
                  Comparable.class))
          .setParallelism(operator.getParallelism());
    }

    return reduced;
  }

  // ------------------------------------------------------------------------------

  static class RBKReducer
        implements ReduceFunction<BatchElement<Window, Pair>> {

    final UnaryFunction<Iterable, Object> reducer;

    RBKReducer(UnaryFunction<Iterable, Object> reducer) {
      this.reducer = reducer;
    }

    @Override
    public BatchElement<Window, Pair>
    reduce(BatchElement<Window, Pair> p1, BatchElement<Window, Pair> p2) {

      Window wid = p1.getWindow();
      return new BatchElement<>(
              wid,
              Math.max(p1.getTimestamp(), p2.getTimestamp()),
              Pair.of(
                      p1.getElement().getKey(),
                      reducer.apply(Arrays.asList(p1.getElement().getSecond(), p2.getElement().getSecond()))));
    }
  }
}
