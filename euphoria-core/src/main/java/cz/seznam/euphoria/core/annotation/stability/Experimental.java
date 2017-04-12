package cz.seznam.euphoria.core.annotation.stability;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Target;

/**
 * Any (API) item annotated with this annotation signals that the item
 * is purely experimental and may be change or completely removed without
 * any notice at any time.
 * <p>
 *
 * Clients should generally avoid usage of such items, except when experimenting :)
 */
@Documented
@Target({
    ElementType.TYPE,
    ElementType.FIELD,
    ElementType.METHOD,
    ElementType.PARAMETER,
    ElementType.CONSTRUCTOR,
    ElementType.ANNOTATION_TYPE,
    ElementType.PACKAGE
})
public @interface Experimental {}
