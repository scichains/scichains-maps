/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2017-2023 Daniel Alievsky, AlgART Laboratory (http://algart.net)
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package net.algart.executors.modules.maps.pyramids.io;

import javax.script.Compilable;
import javax.script.CompiledScript;
import javax.script.ScriptEngine;
import javax.script.ScriptException;
import java.util.Objects;

/**
 * Simple analog of common class {@link net.algart.bridges.standard.JavaScriptPerformer}.
 */
class SimpleFormula {
    private final ScriptEngine context;
    private final String formula;
    private final Long longValue;
    private final CompiledScript compiledFormula;

    public SimpleFormula(ScriptEngine context, String formula) {
        this.context = Objects.requireNonNull(context);
        this.formula = Objects.requireNonNull(formula);
        Long longValue = null;
        try {
            longValue = Long.parseLong(formula);
            // maybe, it is just a simple double value
        } catch (NumberFormatException e) {
            // stay to be null
        }
        this.longValue = longValue;
        try {
            if (longValue == null && context instanceof Compilable) {
                this.compiledFormula = ((Compilable) context).compile(formula);
            } else {
                this.compiledFormula = null;
            }
        } catch (ScriptException e) {
            throw new IllegalArgumentException("Cannot evaluate JavaScript formula \""
                    + formula + "\"", e);
        }
    }

    public void putVariable(String variableName, Object value) {
        context.put(variableName, value);
    }

    @Override
    public String toString() {
        return "formula: " + formula;
    }

    String formula() {
        return formula;
    }

    double evalDouble() {
        if (longValue != null) {
            return longValue;
        }
        final Object eval = evalObject();
        if (eval == null) {
            return 0;
        }
        return Double.parseDouble(eval.toString());
    }

    private Object evalObject() {
        try {
            return compiledFormula == null ?
                    context.eval(formula) :
                    compiledFormula.eval();
        } catch (ScriptException e) {
            throw new IllegalArgumentException("Cannot evaluate JavaScript formula \""
                    + formula + "\"", e);
        }
    }
}
