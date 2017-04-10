package cz.seznam.euphoria.core.client.functional;

import java.io.Serializable;

public interface DelegatedFunction<F> extends Serializable {

  F getDelegate();

}
