/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.maven.plugin.compiler;

import javax.tools.OptionChecker;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.function.UnaryOperator;

import org.apache.maven.api.plugin.Log;

/**
 * An helper class for preparing the options to pass to the tool (compiler or document generator).
 * It does <em>not</em> include the options related to paths (class-path, destination directory, <i>etc.</i>).
 * If an option is unsupported by the tool, a message is logged at the warning level.
 *
 * @author Martin Desruisseaux
 */
public final class Options {
    /**
     * The list of options with their values. For example, in order to compile for Java 17,
     * {@code --release} and {@code 17} shall be two consecutive elements in this list.
     */
    final List<String> options;

    /**
     * Index of the value of the {@code --release} parameter, or 0 if not present.
     *
     * @see #setRelease(String)
     */
    private int indexOfReleaseValue;

    /**
     * The tools to use for checking whether an option is supported.
     * It can be the Java compiler or the Javadoc generator.
     */
    private final OptionChecker checker;

    /**
     * Where to report warnings about unsupported options.
     */
    private final Log logger;

    /**
     * The warning message to log. This is used when a warning is not logged immediately,
     * but deferred for allowing the caller to complete the message before to log.
     */
    private String warning;

    /**
     * Creates an initially empty list of options.
     *
     * @param checker the tools to use for checking whether an option is supported
     * @param logger where to report warnings about unsupported options
     */
    Options(OptionChecker checker, Log logger) {
        options = new ArrayList<>();
        this.checker = checker;
        this.logger = logger;
    }

    /**
     * Strips white spaces and returns the result if non-empty, or {@code null} otherwise.
     *
     * @param value  the value from which to strip white spaces, or {@code null}
     * @return the stripped value, or {@code null} if the value was null or blank
     */
    private static String strip(String value) {
        if (value != null) {
            value = value.strip();
            if (value.isEmpty()) {
                return null;
            }
        }
        return value;
    }

    /**
     * Adds or sets the value of the {@code --release} option. If this option was not present, it is added.
     * If this option has already been specified, then its value is changed to the given value if non-null
     * and non-blank, or removed otherwise.
     *
     * @param value value of the {@code --release} option, or {@code null} or empty if none
     * @return whether the option has been added or defined
     */
    public boolean setRelease(String value) {
        if (indexOfReleaseValue == 0) {
            boolean added = addIfNonBlank("--release", value);
            if (added) {
                indexOfReleaseValue = options.size() - 1;
            }
            return added;
        }
        value = strip(value);
        if (value != null) {
            options.set(indexOfReleaseValue, value);
            return true;
        }
        options.subList(indexOfReleaseValue - 1, indexOfReleaseValue + 1).clear();
        indexOfReleaseValue = 0;
        return false;
    }

    /**
     * Adds the given option if the given value is true and the option is supported.
     * If the option is unsupported, then a warning is logged and the option is not added.
     *
     * @param option the option (e.g. {@code --enable-preview})
     * @param value value of the option
     * @return whether the option has been added
     */
    public boolean addIfTrue(String option, boolean value) {
        if (value && checkNumberOfArguments(option, 0, true)) {
            options.add(option);
            return true;
        }
        return false;
    }

    /**
     * Adds the given option if a non-null and non-blank value is provided and if the option is supported.
     * If the option is unsupported by the tool, then a warning is logged and the option is not added.
     *
     * @param option the option (e.g., {@code --release})
     * @param value value of the option, or {@code null} or blank if none
     * @return whether the option has been added
     */
    public boolean addIfNonBlank(String option, String value) {
        value = strip(value);
        if (value != null) {
            if (checkNumberOfArguments(option, 1, true)) {
                options.add(option);
                options.add(value);
                return true;
            }
        }
        return false;
    }

    /**
     * Adds the given option using the {@code option:values} syntax where {@code values} is a coma-separated list.
     * The option is added only if at least one non-blank value is provided. Values are converted to lower cases.
     * Leading and trailing spaces are removed. If a filter is specified, then that filter will receive the values
     * specified by the users and shall return the values to append, or {@code null} for not appending the option.
     *
     * @param option the option (e.g. {@code -g})
     * @param values coma-separated values of the option, or {@code null} if none
     * @param valids valid values for better error message when needed, or {@code null} if unspecified
     * @param filter filter to apply on the values before to add them, or {@code null} if none
     * @return whether the option has been added
     */
    public boolean addComaSeparated(
            final String option, String values, Collection<String> valids, UnaryOperator<String[]> filter) {
        if (values == null) {
            return false;
        }
        /*
         * Rebuild the comma-separated list of options with spaces removed, empty values omitted and case
         * changed to lower-case. The split list will be reused for diagnostic if the option is not accepted.
         */
        String[] split = values.split(",");
        int count = 0;
        for (String value : split) {
            value = value.strip();
            if (!value.isEmpty()) {
                split[count++] = value.toLowerCase(Locale.US);
            }
        }
        /*
         * If a filter is specified, replace the user-specified list by the filtered one.
         * The filtering may result in an empty list, which is interpreted as an option without value.
         * This is different than an absence of user-supplied values, which is interpreted as no option.
         * This subtle difference explains why the check for absence of values is done before filtering,
         * and is needed for making possible to replace "-g:all" by "-g" (because the "all" value is a
         * Maven addition).
         */
        if (count == 0) {
            return false;
        }
        if (filter != null) {
            if (count != split.length) {
                split = Arrays.copyOfRange(split, 0, count);
            }
            split = filter.apply(split);
            if (split == null) {
                return false;
            }
            count = split.length;
        }
        /*
         * Format the option (possibly with no values), then validate.
         */
        var sb = new StringBuilder(option);
        for (int i = 0; i < count; i++) {
            sb.append(i == 0 ? ':' : ',').append(split[i]);
        }
        String s = sb.toString();
        if (checkNumberOfArguments(s, 0, false)) {
            options.add(s);
            return true;
        }
        /*
         * A log message has been prepared in the `warning` field for saying that the option is not supported.
         * If a collection of valid options was provided, use it for identifying which option was invalid.
         */
        if (valids != null) {
            for (int i = 0; i < count; i++) {
                String value = split[i];
                if (!valids.contains(value)) {
                    sb.setLength(0);
                    sb.append(warning);
                    sb.setLength(sb.length() - 1); // Remove the trailing dot.
                    sb.append(", because the specified ")
                            .append(option)
                            .append(" value '")
                            .append(value)
                            .append("' is unexpected. Legal values are: ");
                    int j = 0;
                    for (String valid : valids) {
                        if (j++ != 0) {
                            sb.append(", ");
                            if (j == valids.size()) {
                                sb.append("and ");
                            }
                        }
                        sb.append('\'').append(valid).append('\'');
                    }
                    warning = sb.append('.').toString();
                    break;
                }
            }
        }
        logger.warn(warning);
        warning = null;
        return false;
    }

    /**
     * Verifies the validity of the given memory setting and adds it as an option.
     * If the value has no units and Maven defaults are enabled, appends "M" as the default units of measurement.
     * Note: in the International System of Units, the symbol shall be upper-case "M". The lower-case "m" symbol
     * is not correct as it stands for "milli".
     *
     * @param option the option (e.g. {@code -J-Xms})
     * @param label name of the XML element or attribute, used only if a warning message needs to be produced
     * @param value the memory setting, or {@code null} if none
     * @param addDefaultUnit whether to add a default unit (currently 'M') if none is provided
     * @return whether the option has been added
     */
    public boolean addMemoryValue(String option, String label, String value, boolean addDefaultUnit) {
        value = strip(value);
        if (value != null) {
            int length = value.length();
            for (int i = 0; i < length; i++) {
                char c = value.charAt(i);
                if (c < '0' || c > '9') { // Do no use `isDigit(…)` because we do not accept other languages.
                    if (i == length - 1) {
                        c = Character.toUpperCase(c);
                        if (c == 'K' || c == 'M' || c == 'G') {
                            addDefaultUnit = false;
                            break;
                        }
                    }
                    logger.warn("Invalid value for " + label + "=\"" + value + "\". Ignoring this option.");
                    return false;
                }
            }
            if (addDefaultUnit) {
                value += 'M'; // Upper case because this is the correct case in International System of Units.
                logger.warn("Value " + label + "=\"" + value + "\" has been specified without unit. "
                        + "An explicit \"M\" unit symbol should be appended for avoiding ambiguity.");
            }
            option += value;
            if (checkNumberOfArguments(option, 0, true)) {
                options.add(option);
                return true;
            }
        }
        return false;
    }

    /**
     * Verifies if the given option is supported and accepts the given number of arguments.
     * If not, a warning is logged if {@code immediate} is {@code true}, or stored in the
     * {@link #warning} field if {@code immediate} is {@code false}.
     *
     * <p>If a message is stored in {@link #warning}, then it will always end with a dot.
     * This guarantee allows callers to delete the last character and replace it by a coma
     * for continuing the sentence.</p>
     *
     * @param option the option to validate
     * @param count the number of arguments that the caller wants to provide
     * @param immediate whether to log immediately or to store the message in {@link #warning}
     * @return whether the given option is supported and accepts the specified number of arguments
     */
    private boolean checkNumberOfArguments(String option, int count, boolean immediate) {
        int expected = checker.isSupportedOption(option);
        if (expected == count) {
            warning = null;
            return true;
        } else if (expected < 1) {
            if (checker instanceof ForkedCompiler) {
                return true; // That implementation actually knows nothing about which options are supported.
            }
            warning = "The '" + option + "' option is not supported.";
        } else if (expected == 0) {
            warning = "The '" + option + "' option does not expect any argument.";
        } else if (expected == 1) {
            warning = "The '" + option + "' option expects a single argument.";
        } else {
            warning = "The '" + option + "' option expects " + expected + " arguments.";
        }
        if (immediate) {
            logger.warn(warning);
            warning = null;
        }
        return false;
    }

    /**
     * Adds the non-null and non-empty elements without verifying their validity.
     * This method is used for user-specified compiler arguments.
     *
     * @param arguments the arguments to add, or {@code null} or empty if none
     */
    public void addUnchecked(Iterable<String> arguments) {
        if (arguments != null) {
            for (String arg : arguments) {
                if (arg != null) {
                    arg = arg.strip();
                    if (!arg.isEmpty()) {
                        options.add(arg);
                    }
                }
            }
        }
    }

    /**
     * Splits the given arguments around spaces, then adds them without verifying their validity.
     * This is used for user-specified arguments.
     *
     * @param arguments the arguments to add, or {@code null} if none
     *
     * @deprecated Use {@link #addUnchecked(Iterable)} instead. This method does not check for quoted strings.
     */
    @Deprecated(since = "4.0.0")
    void addUnchecked(String arguments) {
        if (arguments != null) {
            addUnchecked(Arrays.asList(arguments.split(" ")));
        }
    }

    /**
     * Formats the options for debugging purposes.
     *
     * @param commandLine the prefix where to put the {@code -J} options before all other options
     * @param out where to put all options other than {@code -J}
     * @throws IOException if an error occurred while writing an option
     */
    void format(final StringBuilder commandLine, final Appendable out) throws IOException {
        boolean hasOptions = false;
        for (String option : options) {
            if (option.isBlank()) {
                continue;
            }
            if (option.startsWith("-J")) {
                if (commandLine != null) {
                    if (commandLine.length() != 0) {
                        commandLine.append(' ');
                    }
                    commandLine.append(option);
                }
                continue;
            }
            if (hasOptions) {
                if (option.charAt(0) == '-') {
                    out.append(System.lineSeparator());
                } else {
                    out.append(' ');
                }
            }
            boolean needsQuote = option.indexOf(' ') >= 0;
            if (needsQuote) {
                out.append(AbstractCompilerMojo.QUOTE);
            }
            out.append(option);
            if (needsQuote) {
                out.append(AbstractCompilerMojo.QUOTE);
            }
            hasOptions = true;
        }
        if (hasOptions) {
            out.append(System.lineSeparator());
        }
    }

    /**
     * {@return a string representatation of the options for debugging purposes}
     */
    @Override
    public String toString() {
        var out = new StringBuilder(40);
        try {
            format(out, out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toString();
    }
}
