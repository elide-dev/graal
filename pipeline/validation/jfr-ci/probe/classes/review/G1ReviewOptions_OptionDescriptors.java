// CheckStyle: stop header check
// CheckStyle: stop line length check
// GENERATED CONTENT - DO NOT EDIT
// Source: G1ReviewOptions.java
package review;

import java.util.*;
import jdk.graal.compiler.options.*;
import jdk.graal.compiler.options.OptionType;
import jdk.graal.compiler.options.OptionStability;

public class G1ReviewOptions_OptionDescriptors implements OptionDescriptors {
    private OptionsContainer cachedContainer;
    @Override
    public OptionsContainer getContainer() {
        if (cachedContainer == null) {
            cachedContainer = new OptionsContainer.Default(G1ReviewOptions.class);
        }
        return cachedContainer;
    }

    @Override
    public OptionDescriptor get(String value) {
        switch (getContainer().unprefixed(value)) {
        // CheckStyle: stop line length check
        case "ReviewG1": {
            return OptionDescriptor.create(
                /*name*/ "ReviewG1",
                /*optionType*/ OptionType.Debug,
                /*optionValueType*/ Boolean.class,
                /*help*/ "Register public G1 for an isolated review image",
                /*container*/ getContainer(),
                /*option*/ G1ReviewOptions.ReviewG1,
                /*fieldName*/ "ReviewG1",
                /*stability*/ OptionStability.EXPERIMENTAL,
                /*deprecated*/ false,
                /*deprecationMessage*/ "");
        }
        // CheckStyle: resume line length check
        }
        return null;
    }

    @Override
    public Iterator<OptionDescriptor> iterator() {
        return new Iterator<>() {
            int i = 0;
            @Override
            public boolean hasNext() {
                return i < 1;
            }
            @Override
            public OptionDescriptor next() {
                switch (i++) {
                    case 0: return get("ReviewG1");
                }
                throw new NoSuchElementException();
            }
        };
    }
}
