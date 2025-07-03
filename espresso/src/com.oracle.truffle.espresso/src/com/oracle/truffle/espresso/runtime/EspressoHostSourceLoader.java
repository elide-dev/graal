package com.oracle.truffle.espresso.runtime;

import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.espresso.impl.ObjectKlass;

/**
 * Interface for hosts to implement, which provides an opportunity to resolve source code for a given Espresso guest
 * class.
 */
public interface EspressoHostSourceLoader {
    /**
     * Loads the source code for a given class object.
     *
     * @param klass the guest class to retrieve source for.
     * @return the source for the guest class, or `null` if no source is available.
     */
    Source getSourceForGuestClass(ObjectKlass klass);

    // Default implementation of `EspressoHostSourceLoader` that returns `null` for all requests.
    class DefaultHostSourceLoader implements EspressoHostSourceLoader {
        @Override
        public Source getSourceForGuestClass(ObjectKlass klass) {
            return null;
        }
    }
}
