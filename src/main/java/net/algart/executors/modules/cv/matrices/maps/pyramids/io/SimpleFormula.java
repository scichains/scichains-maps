package net.algart.executors.modules.cv.matrices.maps.pyramids.io;

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
