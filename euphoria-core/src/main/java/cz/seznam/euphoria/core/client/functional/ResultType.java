package cz.seznam.euphoria.core.client.functional;

import cz.seznam.euphoria.shaded.guava.com.google.common.reflect.TypeToken;

import java.io.Serializable;
import java.lang.reflect.Type;

// XXX TypeHint would be a much better name but is already take by flink :/
public abstract class ResultType<T> implements Serializable {
  private final TypeToken<T> tt = new TypeToken<T>(getClass()) {};

  public final Type getType() {
    return tt.getType();
  }

  public final Class<?> getRawType() {
    return tt.getRawType();
  }
}
