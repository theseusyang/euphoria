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
package cz.seznam.euphoria.core.client.operator;

import cz.seznam.euphoria.core.client.functional.DelegatedFunction;
import cz.seznam.euphoria.core.client.functional.ResultType;
import cz.seznam.euphoria.core.client.functional.ResultTypeAware;
import cz.seznam.euphoria.core.client.functional.UnaryFunction;

import java.io.Serializable;
import java.util.Objects;

class TypeSupport {

  abstract static class FunctionMeta<F, T>
      implements Serializable, DelegatedFunction<F>, ResultTypeAware<T> {
    private final F function;
    private final ResultType<T> resultType;

    FunctionMeta(F function, ResultType<T> resultType) {
      this.function = Objects.requireNonNull(function);
      this.resultType = Objects.requireNonNull(resultType);
    }

    @Override
    public F getDelegate() {
      return function;
    }

    @Override
    public ResultType<T> getResultType() {
      return resultType;
    }
  }

  static final class DelegatedUnaryFunction<I, O>
      extends FunctionMeta<UnaryFunction<I, O>, O>
      implements UnaryFunction<I, O> {

    DelegatedUnaryFunction(UnaryFunction<I, O> function, ResultType<O> resultType) {
      super(function, resultType);
    }

    @Override
    public O apply(I what) {
      return getDelegate().apply(what);
    }
  }

  private TypeSupport() {}
}