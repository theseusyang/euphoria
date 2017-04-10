package cz.seznam.euphoria.flink.types;

import cz.seznam.euphoria.core.client.dataset.windowing.Window;
import cz.seznam.euphoria.core.client.functional.DelegatedFunction;
import cz.seznam.euphoria.core.client.functional.ResultType;
import cz.seznam.euphoria.core.client.functional.ResultTypeAware;
import cz.seznam.euphoria.core.client.functional.UnaryFunction;
import cz.seznam.euphoria.core.client.util.Pair;
import cz.seznam.euphoria.flink.batch.BatchElement;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.DataSet;
import org.apache.flink.api.java.typeutils.PojoField;
import org.apache.flink.api.java.typeutils.PojoTypeInfo;
import org.apache.flink.api.java.typeutils.TypeExtractor;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class TypeSupport {

  public static class FunctionMeta<F> {
    public final F function;
    public final ResultType rtype;

    @SuppressWarnings("unchecked")
    public static <I, O> FunctionMeta<UnaryFunction<I, O>> of(UnaryFunction<I, O> f) {
      Objects.requireNonNull(f);

      // ~ get the result type - if any
      ResultType userDefinedType = null;
      if (f instanceof ResultTypeAware) {
        userDefinedType = ((ResultTypeAware) f).getResultType();
      }
      // ~ if the function (object) merely delegates somewhere else,
      // strip it off and retrieve the original function
      if (f instanceof DelegatedFunction) {
        Object delegate = ((DelegatedFunction) f).getDelegate();
        if (!(delegate instanceof UnaryFunction)) {
          // ~ captures `f == null` as well
          throw new IllegalStateException("Delegation mixed up!");
        }
        f = (UnaryFunction) delegate;
      }
      return new FunctionMeta<>(f, userDefinedType);
    }

    private FunctionMeta(F function, ResultType rtype) {
      this.function = function;
      this.rtype = rtype;
    }
  }

  public static <K, V> TypeInformation<Pair<K, V>>
  forPair(ResultType<K> firstType, ResultType<V> secondType) {
    return forPair(toTypeInfo(firstType), toTypeInfo(secondType));
  }

  @SuppressWarnings("unchecked")
  public static <K, V> TypeInformation<Pair<K, V>>
  forPair(TypeInformation<K> firstType, TypeInformation<V> secondType) {
    return (TypeInformation) new PairTypeInfo(
        Objects.requireNonNull(firstType), Objects.requireNonNull(secondType));
  }

  @SuppressWarnings("unchecked")
  public static <W extends Window, T> TypeInformation<BatchElement<W, T>>
  forBatchElement(ResultType<W> windowType, ResultType<T> elementType) {
    return forBatchElement(toTypeInfo(windowType, Window.class), toTypeInfo(elementType));
  }

  @SuppressWarnings("unchecked")
  public static <W extends Window, T> TypeInformation<BatchElement<W, T>>
  forBatchElement(ResultType<W> windowType, TypeInformation<T> elementType) {
    return forBatchElement(toTypeInfo(windowType, Window.class), elementType);
  }

  @SuppressWarnings("unchecked")
  public static <W extends Window, T> TypeInformation<BatchElement<W, T>>
  forBatchElement(TypeInformation<W> windowType, TypeInformation<T> elementType) {
    Objects.requireNonNull(windowType);
    Objects.requireNonNull(elementType);
    TypeInformation<?> timestampType = TypeInformation.of(Long.TYPE);

    List<PojoField> fields = new ArrayList<>(2);
    try {
      fields.add(new PojoField(BatchElement.class.getDeclaredField("window"), windowType));
      fields.add(new PojoField(BatchElement.class.getDeclaredField("timestamp"), timestampType));
      fields.add(new PojoField(BatchElement.class.getDeclaredField("element"), elementType));
    } catch (NoSuchFieldException e) {
      throw new IllegalStateException(e);
    }
    return new PojoTypeInfo(BatchElement.class, fields);
  }

  @SuppressWarnings("unchecked")
  public static <T> TypeInformation<T> toTypeInfo(ResultType<T> rt) {
    return toTypeInfo(rt, Object.class);
  }

  @SuppressWarnings("unchecked")
  public static <T> TypeInformation<T> toTypeInfo(ResultType<T> rt, Class fallback) {
    Objects.requireNonNull(fallback);
    return (TypeInformation<T>) (rt == null
        ? TypeExtractor.createTypeInfo(fallback)
        : TypeExtractor.createTypeInfo(rt.getType()));
  }

  @SuppressWarnings("unchecked")
  public static <W extends Window> TypeInformation<W>
  extractWindowType(DataSet<BatchElement<W, ?>> dataset) {
    TypeInformation<BatchElement<W, ?>> type = dataset.getType();
    if (type instanceof PojoTypeInfo) {
      return ((PojoTypeInfo) type).getTypeAt("window");
    }
    return null;
  }

  private TypeSupport() {}
}
