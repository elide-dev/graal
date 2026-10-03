/*
 * Copyright (c) 2017, 2024, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.oracle.truffle.polyglot;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Formatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

import org.graalvm.options.ConstantOptionKey;
import org.graalvm.options.OptionDescriptor;
import org.graalvm.options.OptionDescriptors;
import org.graalvm.options.OptionKey;
import org.graalvm.options.OptionStability;
import org.graalvm.options.OptionType;
import org.graalvm.options.OptionValues;
import org.graalvm.polyglot.SandboxPolicy;

import com.oracle.truffle.api.TruffleOptionDescriptors;

final class OptionValuesImpl implements OptionValues {

    private static final float FUZZY_MATCH_THRESHOLD = 0.7F;

    private final OptionDescriptors descriptors;
    private final SandboxPolicy sandboxPolicy;
    private final Map<OptionKey<?>, Object> values;
    private List<OptionDescriptor> usedDeprecatedDescriptors;
    private volatile Set<OptionKey<?>> validAssertKeys;
    private final boolean trackDeprecatedOptions;
    /**
     * Options parsed during context pre-initialization, by option name (see
     * {@link #put(String, String, boolean, Supplier)}). Null if there are none.
     */
    private final Map<String, ParsedOption> preparsedOptions;
    /** Records the parsed options for an engine that pre-initializes a context; null otherwise. */
    private final Map<String, ParsedOption> parsedOptions;

    /**
     * An option value parsed from {@code value}. {@code convertedValue} is null unless the option
     * type's converter is known to depend only on the string.
     */
    record ParsedOption(String value, OptionDescriptor descriptor, Object convertedValue) {
    }

    /** The built-in option types, whose converters depend only on the string. */
    private static final Set<OptionType<?>> PURE_OPTION_TYPES = Collections.newSetFromMap(new IdentityHashMap<>());
    static {
        for (Class<?> type : List.of(Boolean.class, Byte.class, Integer.class, Long.class, Float.class, Double.class, String.class)) {
            PURE_OPTION_TYPES.add(OptionType.defaultType(type));
        }
    }

    OptionValuesImpl(OptionDescriptors descriptors, SandboxPolicy sandboxPolicy, boolean trackDeprecatedOptions) {
        this(descriptors, sandboxPolicy, trackDeprecatedOptions, null, false);
    }

    /**
     * @param preparsedOptions options parsed during context pre-initialization, see
     *            {@link #getParsedOptions()}
     * @param recordParsedOptions whether to record the parsed options, for an engine that
     *            pre-initializes a context
     */
    OptionValuesImpl(OptionDescriptors descriptors, SandboxPolicy sandboxPolicy, boolean trackDeprecatedOptions, Map<String, ParsedOption> preparsedOptions, boolean recordParsedOptions) {
        Objects.requireNonNull(descriptors);
        Objects.requireNonNull(sandboxPolicy);
        this.descriptors = descriptors;
        this.sandboxPolicy = sandboxPolicy;
        this.values = new HashMap<>();
        this.trackDeprecatedOptions = trackDeprecatedOptions;
        this.preparsedOptions = preparsedOptions;
        this.parsedOptions = recordParsedOptions ? new HashMap<>() : null;
    }

    private OptionValuesImpl(OptionValuesImpl copy) {
        this.values = new HashMap<>(copy.values);
        this.descriptors = copy.descriptors;
        this.sandboxPolicy = copy.sandboxPolicy;
        this.usedDeprecatedDescriptors = copy.usedDeprecatedDescriptors;
        this.trackDeprecatedOptions = copy.trackDeprecatedOptions;
        // Context options are parsed into a copy of the engine options; the map is immutable.
        this.preparsedOptions = copy.preparsedOptions;
        this.parsedOptions = null;
    }

    /**
     * Returns the recorded parsed options, or null if these values do not record them.
     */
    Map<String, ParsedOption> getParsedOptions() {
        return parsedOptions == null ? null : Map.copyOf(parsedOptions);
    }

    @Override
    public int hashCode() {
        int result = 31 + descriptors.hashCode();
        result = 31 * result + values.hashCode();
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof OptionValues)) {
            return super.equals(obj);
        } else {
            if (this == obj) {
                return true;
            }
            OptionValues other = ((OptionValues) obj);
            if (!getDescriptors().equals(other.getDescriptors())) {
                return false;
            }
            if (!hasSetOptions() && !other.hasSetOptions()) {
                return true;
            }
            if (other instanceof OptionValuesImpl) {
                // faster comparison that only depends on the set values
                OptionValuesImpl otherOptions = (OptionValuesImpl) other;
                if (!values.equals(otherOptions.values)) {
                    return false;
                }
            } else {
                // slow comparison for arbitrary option values
                for (OptionDescriptor descriptor : getDescriptors()) {
                    OptionKey<?> key = descriptor.getKey();
                    if (!slowCompareKey(key, other)) {
                        return false;
                    }
                }
            }
            return true;
        }
    }

    Collection<OptionDescriptor> getUsedDeprecatedDescriptors() {
        if (!trackDeprecatedOptions) {
            throw new UnsupportedOperationException("Deprecated options not tracked.");
        }
        if (usedDeprecatedDescriptors == null) {
            return List.of();
        }
        return usedDeprecatedDescriptors;
    }

    private boolean slowCompareKey(OptionKey<?> key, OptionValues other) {
        boolean set = hasBeenSet(key);
        if (set != other.hasBeenSet(key)) {
            return false;
        }
        if (set && !get(key).equals(other.get(key))) {
            return false;
        }
        return true;
    }

    public void putAll(Map<String, String> providedValues, boolean allowExperimentalOptions, Supplier<OptionDescriptors> allOptionsSupplier) {
        for (String key : providedValues.keySet()) {
            put(key, providedValues.get(key), allowExperimentalOptions, allOptionsSupplier);
        }
    }

    /**
     * Parses {@code value} into the option {@code key}.
     *
     * When patching a pre-initialized context, the same option strings are usually parsed again. An
     * option parsed from the same string during context pre-initialization reuses its descriptor,
     * and, for the built-in option types, its converted value. Every other check still runs.
     */
    public OptionDescriptor put(String key, String value, boolean allowExperimentalOptions, Supplier<OptionDescriptors> allOptionsSupplier) {
        ParsedOption preparsed = preparsedOptions == null ? null : preparsedOptions.get(key);
        if (preparsed != null && (!preparsed.value().equals(value) ||
                        (!allowExperimentalOptions && preparsed.descriptor().getStability() == OptionStability.EXPERIMENTAL))) {
            preparsed = null;
        }
        OptionDescriptor descriptor = preparsed != null ? preparsed.descriptor() : findDescriptor(key, allowExperimentalOptions, allOptionsSupplier);
        if (sandboxPolicy != SandboxPolicy.TRUSTED) {
            SandboxPolicy optionSandboxPolicy = descriptors instanceof TruffleOptionDescriptors ? ((TruffleOptionDescriptors) descriptors).getSandboxPolicy(key) : SandboxPolicy.TRUSTED;
            if (sandboxPolicy.isStricterThan(optionSandboxPolicy)) {
                throw PolyglotEngineException.illegalArgument(PolyglotImpl.sandboxPolicyException(sandboxPolicy,
                                String.format("The option %s can only be used up to the %s sandbox policy.", descriptor.getName(), optionSandboxPolicy),
                                String.format("do not set the %s option by removing Builder.option(\"%s\", \"%s\")", descriptor.getName(), descriptor.getName(), value)));
            }
        }
        OptionKey<?> optionKey = descriptor.getKey();
        Object convertedValue;
        if (preparsed != null && preparsed.convertedValue() != null) {
            convertedValue = preparsed.convertedValue();
        } else {
            convertedValue = convert(key, value, descriptor);
        }
        if (descriptor.isConstant()) {
            if (optionKey instanceof ConstantOptionKey<?> constantOptionKey) {
                Object fixedValue = constantOptionKey.getConstantValue();
                if (!Objects.equals(convertedValue, fixedValue)) {
                    throw PolyglotEngineException.illegalArgument(String.format(
                                    "Option '%s' is constant and is already fixed to '%s'. " +
                                                    "Engine|Context.Builder.option() may repeat that value, but cannot change it to '%s'. " +
                                                    "To choose a different value, set -Dpolyglot.%s=<value> before the polyglot runtime is initialized " +
                                                    "(HotSpot: JVM command line; native image: native-image build).",
                                    descriptor.getName(), fixedValue, value, descriptor.getName()));
                }
            } else {
                throw PolyglotEngineException.illegalArgument(String.format("Option '%s' marked constant must use ConstantOptionKey.", descriptor.getName()));
            }
        }
        if (trackDeprecatedOptions && descriptor.isDeprecated()) {
            if (usedDeprecatedDescriptors == null) {
                usedDeprecatedDescriptors = new ArrayList<>();
            }
            usedDeprecatedDescriptors.add(descriptor);
        }
        values.put(descriptor.getKey(), convertedValue);
        if (parsedOptions != null && !descriptor.isOptionMap()) {
            parsedOptions.put(key, new ParsedOption(value, descriptor, PURE_OPTION_TYPES.contains(optionKey.getType()) ? convertedValue : null));
        }
        return descriptor;
    }

    private Object convert(String key, String value, OptionDescriptor descriptor) {
        OptionKey<?> optionKey = descriptor.getKey();
        Object previousValue;
        if (values.containsKey(optionKey)) {
            previousValue = values.get(optionKey);
        } else {
            previousValue = optionKey.getDefaultValue();
        }
        String name = descriptor.getName();
        String suffix = null;
        if (descriptor.isOptionMap()) {
            suffix = key.substring(name.length());
            assert suffix.isEmpty() || suffix.startsWith(".");
            if (suffix.startsWith(".")) {
                suffix = suffix.substring(1);
            }
        }
        try {
            return optionKey.getType().convert(previousValue, suffix, value);
        } catch (IllegalArgumentException e) {
            throw PolyglotEngineException.illegalArgument(e);
        }
    }

    private <T> boolean contains(OptionKey<T> optionKey) {
        /*
         * Iterating the keys is too slow so we use a cached set to speed up the check.
         */
        Set<OptionKey<?>> keys = this.validAssertKeys;
        if (keys == null) {
            keys = initializeValidAssertKeys();
        }
        return keys.contains(optionKey);
    }

    private synchronized Set<OptionKey<?>> initializeValidAssertKeys() {
        Set<OptionKey<?>> keys = validAssertKeys;
        if (keys == null) {
            keys = new HashSet<>();
            for (OptionDescriptor descriptor : descriptors) {
                keys.add(descriptor.getKey());
            }
            validAssertKeys = keys;
        }
        return keys;
    }

    @Override
    public boolean hasBeenSet(OptionKey<?> optionKey) {
        assert contains(optionKey);
        return values.containsKey(optionKey);
    }

    OptionValuesImpl copy() {
        return new OptionValuesImpl(this);
    }

    void copyInto(OptionValuesImpl target) {
        if (!target.values.isEmpty()) {
            throw new IllegalStateException("Values must be empty.");
        }
        if (sandboxPolicy != target.sandboxPolicy) {
            throw new AssertionError("Source and target must have the same SandboxPolicy.");
        }
        target.values.putAll(values);
    }

    public OptionDescriptors getDescriptors() {
        return descriptors;
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T get(OptionKey<T> optionKey) {
        assert contains(optionKey);
        Object value = values.get(optionKey);
        if (value == null) {
            return optionKey.getDefaultValue();
        }
        return (T) value;
    }

    @SuppressWarnings("deprecation")
    @Override
    public <T> void set(OptionKey<T> optionKey, T value) {
        throw new UnsupportedOperationException("OptionValues#set() is no longer supported");
    }

    @Override
    public boolean hasSetOptions() {
        return !values.isEmpty();
    }

    private OptionDescriptor findDescriptor(String key, boolean allowExperimentalOptions, Supplier<OptionDescriptors> allOptionsSupplier) {
        OptionDescriptor descriptor = descriptors.get(key);
        if (descriptor == null) {
            throw failNotFound(key, allOptionsSupplier);
        }
        if (!allowExperimentalOptions && descriptor.getStability() == OptionStability.EXPERIMENTAL) {
            throw failExperimental(key);
        }
        return descriptor;
    }

    private static RuntimeException failExperimental(String key) {
        final String message = String.format("Option '%s' is experimental and must be enabled with allowExperimentalOptions(boolean) in Context.Builder or Engine.Builder. ", key) +
                        "Do not use experimental options in production environments.";
        return PolyglotEngineException.illegalArgument(message);
    }

    private RuntimeException failNotFound(String key, Supplier<OptionDescriptors> allOptionsSupplier) {
        OptionDescriptors allOptions;
        Exception errorOptions = null;
        try {
            allOptions = allOptionsSupplier == null ? this.descriptors : allOptionsSupplier.get();
        } catch (Exception e) {
            errorOptions = e;
            allOptions = this.descriptors;
        }
        RuntimeException error = failNotFound(allOptions, key);
        if (errorOptions != null) {
            error.addSuppressed(errorOptions);
        }

        throw error;
    }

    static RuntimeException failNotFound(OptionDescriptors allOptions, String key) {
        Iterable<OptionDescriptor> matches = fuzzyMatch(allOptions, key);
        Formatter msg = new Formatter();
        msg.format("Could not find option with name %s.", key);

        Iterator<OptionDescriptor> iterator = matches.iterator();
        if (iterator.hasNext()) {
            msg.format("%nDid you mean one of the following?");
            for (OptionDescriptor match : matches) {
                msg.format("%n    %s=<%s>", match.getName(), match.getKey().getType().getName());
            }
        }
        throw PolyglotEngineException.illegalArgument(msg.toString());
    }

    /**
     * Returns the set of options that fuzzy match a given option name.
     */
    static List<OptionDescriptor> fuzzyMatch(OptionDescriptors descriptors, String optionKey) {
        List<OptionDescriptor> matches = new ArrayList<>();
        for (org.graalvm.options.OptionDescriptor option : descriptors) {
            float score = stringSimiliarity(option.getName(), optionKey);
            if (score >= FUZZY_MATCH_THRESHOLD) {
                matches.add(option);
            }
        }
        return matches;
    }

    /**
     * Compute string similarity based on Dice's coefficient.
     *
     * Ported from str_similar() in globals.cpp.
     */
    private static float stringSimiliarity(String str1, String str2) {
        int hit = 0;
        for (int i = 0; i < str1.length() - 1; ++i) {
            for (int j = 0; j < str2.length() - 1; ++j) {
                if ((str1.charAt(i) == str2.charAt(j)) && (str1.charAt(i + 1) == str2.charAt(j + 1))) {
                    ++hit;
                    break;
                }
            }
        }
        return 2.0f * hit / (str1.length() + str2.length());
    }

    @Override
    public String toString() {
        StringBuilder b = new StringBuilder("{");
        String sep = "";
        for (OptionDescriptor descriptor : getDescriptors()) {
            OptionKey<?> key = descriptor.getKey();
            if (hasBeenSet(key)) {
                b.append(sep);
                b.append(descriptor.getName());
                b.append("=");
                b.append(values.get(key));
                sep = ", ";
            }
        }
        b.append("}");
        return b.toString();
    }
}
