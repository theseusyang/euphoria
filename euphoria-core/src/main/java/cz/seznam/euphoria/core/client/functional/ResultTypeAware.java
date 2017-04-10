package cz.seznam.euphoria.core.client.functional;

import java.io.Serializable;

public interface ResultTypeAware<T> extends Serializable {

  ResultType<T> getResultType();

}
