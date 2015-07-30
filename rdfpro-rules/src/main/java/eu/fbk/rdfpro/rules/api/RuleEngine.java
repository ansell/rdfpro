package eu.fbk.rdfpro.rules.api;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Nullable;

import com.google.common.base.Throwables;

import org.openrdf.model.Statement;
import org.openrdf.query.BindingSet;
import org.openrdf.rio.RDFHandler;
import org.openrdf.rio.RDFHandlerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import eu.fbk.rdfpro.AbstractRDFHandlerWrapper;
import eu.fbk.rdfpro.RDFHandlers;
import eu.fbk.rdfpro.rules.model.QuadModel;
import eu.fbk.rdfpro.util.Environment;
import eu.fbk.rdfpro.util.IO;

/**
 * Rule engine abstraction.
 * <p>
 * Implementation note: concrete rule engine implementations should extend this abstract class and
 * implement one or both methods {@link #doEval(Callback, QuadModel)} and
 * {@link #doEval(Callback, RDFHandler)}.
 * </p>
 */
public abstract class RuleEngine {

    private static final Logger LOGGER = LoggerFactory.getLogger(RuleEngine.class);

    private static final String IMPLEMENTATION = Environment.getProperty(
            "rdfpro.rules.implementation", "eu.fbk.rdfpro.rules.seminaive.SemiNaiveRuleEngine");

    protected final Ruleset ruleset;

    /**
     * Creates a new {@code RuleEngine} using the {@code Ruleset} specified. The ruleset must not
     * contain unsafe rules.
     *
     * @param ruleset
     *            the ruleset, not null and without unsafe rules
     */
    protected RuleEngine(final Ruleset ruleset) {

        // Check the input ruleset
        Objects.requireNonNull(ruleset);
        for (final Rule rule : ruleset.getRules()) {
            if (!rule.isSafe()) {
                throw new IllegalArgumentException("Ruleset contains unsafe rule " + rule);
            }
        }

        // Store the ruleset
        this.ruleset = ruleset;
    }

    /**
     * Factory method for creating a new {@code RuleEngine} using the {@code Ruleset} specified.
     * The ruleset must not contain unsafe rules. The engine implementation instantiated is based
     * on the value of configuration property {@code rdfpro.rules.implementation}, which contains
     * the qualified name of a concrete class extending abstract class {@code RuleEngine}.
     *
     * @param ruleset
     *            the ruleset, not null and without unsafe rules
     * @return the created rule engine
     */
    public static RuleEngine create(final Ruleset ruleset) {

        // Check parameters
        Objects.requireNonNull(ruleset);

        try {
            // Log the operation
            if (LOGGER.isTraceEnabled()) {
                LOGGER.trace("Creating '{}' engine with ruleset:\n{}\n", IMPLEMENTATION, ruleset);
            }

            // Locate the RuleEngine constructor to be used
            final Class<?> clazz = Class.forName(IMPLEMENTATION);
            final Constructor<?> constructor = clazz.getConstructor(Ruleset.class);

            // Instantiate the engine via reflection
            return (RuleEngine) constructor.newInstance(ruleset);

        } catch (final IllegalAccessException | ClassNotFoundException | NoSuchMethodException
                | InstantiationException ex) {
            // Configuration is wrong
            throw new Error("Illegal rule engine implementation: " + IMPLEMENTATION, ex);

        } catch (final InvocationTargetException ex) {
            // Configuration is ok, but the RuleEngine cannot be created
            throw Throwables.propagate(ex.getCause());
        }
    }

    /**
     * Returns the ruleset applied by this engine
     *
     * @return the ruleset
     */
    public final Ruleset getRuleset() {
        return this.ruleset;
    }

    /**
     * Evaluates rules on the {@code QuadModel} specified, possibly invoking an optional
     * {@code Callback}.
     *
     * @param callback
     *            the optional callback
     * @param model
     *            the model the engine will operate on
     */
    public final void eval(@Nullable final Callback callback, final QuadModel model) {

        // Check parameters
        Objects.requireNonNull(model);

        // Handle two cases, respectively with/without logging information emitted
        if (!LOGGER.isDebugEnabled()) {

            // Logging disabled: directly forward to doEval()
            doEval(callback, model);

        } else {

            // Logging enabled: log relevant info before and after forwarding to doEval()
            final long ts = System.currentTimeMillis();
            final int inputSize = model.size();
            LOGGER.debug("Rule evaluation started: {} input statements, {} rule(s){}, "
                    + "model input", inputSize, this.ruleset.getRules().size(),
                    callback == null ? "" : ", with callback");
            doEval(callback, model);
            LOGGER.debug(
                    "Rule evaluation completed: {} input statements, {} output statements, {} ms",
                    inputSize, model.size(), System.currentTimeMillis() - ts);
        }
    }

    /**
     * Evalutes rules in streaming mode, emitting resulting statements to the {@code RDFHandler}
     * supplied and optionally invoking a given {@code Callback}.
     *
     * @param callback
     *            the optional callback
     * @param handler
     *            the handler where to emit resulting statements
     * @return an {@code RDFHandler} where input statements can be streamed into
     */
    public final RDFHandler eval(@Nullable final Callback callback, final RDFHandler handler) {

        // Check parameters
        Objects.requireNonNull(handler);

        // Handle two cases, respectively with/without logging information emitted
        if (!LOGGER.isDebugEnabled()) {

            // Logging disabled: delegate to doEval(), filtering out non-matchable quads
            final RDFHandler sink = handler;
            return new AbstractRDFHandlerWrapper(doEval(callback, handler)) {

                @Override
                public void handleStatement(final Statement stmt) throws RDFHandlerException {
                    if (RuleEngine.this.ruleset.isMatchable(stmt)) {
                        super.handleStatement(stmt);
                    } else {
                        sink.handleStatement(stmt);
                    }
                }

            };

        } else {

            // Logging enabled: allocate counters to track quads in (processed/propagated) and out
            final AtomicInteger numProcessed = new AtomicInteger(0);
            final AtomicInteger numPropagated = new AtomicInteger(0);
            final AtomicInteger numOut = new AtomicInteger(0);

            // Wrap sink handler to count out quads
            final RDFHandler sink = new AbstractRDFHandlerWrapper(handler) {

                @Override
                public void handleStatement(final Statement statement) throws RDFHandlerException {
                    super.handleStatement(statement);
                    numOut.incrementAndGet();
                }

            };

            // Delegate to doEval(), wrapping the returned handler to perform logging and filter
            // out non-matchable quads
            return new AbstractRDFHandlerWrapper(doEval(callback, sink)) {

                private long ts;

                @Override
                public void startRDF() throws RDFHandlerException {
                    this.ts = System.currentTimeMillis();
                    numProcessed.set(0);
                    numPropagated.set(0);
                    numOut.set(0);
                    LOGGER.debug("Rule evaluation started: {} rule(s){}, stream input",
                            RuleEngine.this.ruleset.getRules().size(), callback == null ? ""
                                    : ", with callback");
                    super.startRDF();
                }

                @Override
                public void handleStatement(final Statement stmt) throws RDFHandlerException {
                    if (RuleEngine.this.ruleset.isMatchable(stmt)) {
                        super.handleStatement(stmt);
                        numProcessed.incrementAndGet();
                    } else {
                        this.handler.handleStatement(stmt);
                        numPropagated.incrementAndGet();
                    }
                }

                @Override
                public void endRDF() throws RDFHandlerException {
                    super.endRDF();
                    LOGGER.debug("{}/{} statements directly emitted", numPropagated.get(),
                            numPropagated.get() + numProcessed.get());
                    LOGGER.debug("Rule evaluation completed: {} input statements, "
                            + "{} output statements, {} ms",
                            numProcessed.get() + numPropagated.get(), numOut.get(),
                            System.currentTimeMillis() - this.ts);
                }

            };
        }
    }

    /**
     * Internal method called by {@link #eval(Callback, QuadModel)}. Its base implementation
     * delegates to {@link #doEval(Callback, RDFHandler)}.
     *
     * @param callback
     *            the optional callback
     * @param model
     *            the model to operate on
     */
    protected void doEval(@Nullable final Callback callback, final QuadModel model) {

        // Counters used for logging
        final int numInput = LOGGER.isDebugEnabled() ? model.size() : 0;
        int numPropagated = 0;

        // Delegate to doEval(Callback, RDFHandler), handling two cases for performance reasons
        if (!this.ruleset.isDeletePossible()) {

            // Optimized version that adds inferred statement back to the supplied model, relying
            // on the fact that no statement can be possibly deleted
            final List<Statement> inputStmts = new ArrayList<>(model);
            final RDFHandler handler = doEval(callback, RDFHandlers.wrap(model));
            try {
                handler.startRDF();
                for (final Statement stmt : inputStmts) {
                    if (RuleEngine.this.ruleset.isMatchable(stmt)) {
                        handler.handleStatement(stmt);
                    } else {
                        ++numPropagated;
                    }
                }
                handler.endRDF();
            } catch (final RDFHandlerException ex) {
                throw new RuntimeException(ex);
            } finally {
                IO.closeQuietly(handler);
            }

        } else {

            // General implementation that stores resulting statement in a list, and then clears
            // the input model and loads those statement (this will also take into consideration
            // possible deletions)
            final List<Statement> outputStmts = new ArrayList<>();
            final RDFHandler handler = doEval(callback, RDFHandlers.wrap(outputStmts));
            try {
                handler.startRDF();
                for (final Statement stmt : model) {
                    if (RuleEngine.this.ruleset.isMatchable(stmt)) {
                        handler.handleStatement(stmt);
                    } else {
                        outputStmts.add(stmt);
                        ++numPropagated;
                    }
                }
                handler.endRDF();
            } catch (final RDFHandlerException ex) {
                throw new RuntimeException(ex);
            } finally {
                IO.closeQuietly(handler);
            }
            model.clear();
            for (final Statement stmt : outputStmts) {
                model.add(stmt);
            }
        }

        // Log the number of input statements directly emitted in output as non matchable
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("{}/{} input statements directly emitted", numPropagated, numInput);
        }
    }

    /**
     * Internal method called by {@link #eval(Callback, RDFHandler)}. Its base implementation
     * delegates to {@link #doEval(Callback, QuadModel)}.
     *
     * @param callback
     *            the optional callback
     * @param handler
     *            the handler where to emit resulting statements
     * @return an handler accepting input statements
     */
    protected RDFHandler doEval(@Nullable final Callback callback, final RDFHandler handler) {

        // Return an RDFHandler that delegates to doEval(Callback, QuadModel)
        return new AbstractRDFHandlerWrapper(handler) {

            private QuadModel model;

            @Override
            public void startRDF() throws RDFHandlerException {
                super.startRDF();
                this.model = QuadModel.create();
            }

            @Override
            public synchronized void handleStatement(final Statement stmt)
                    throws RDFHandlerException {
                this.model.add(stmt);
            }

            @Override
            public void endRDF() throws RDFHandlerException {
                doEval(callback, this.model);
                for (final Statement stmt : this.model) {
                    super.handleStatement(stmt);
                }
                this.model = null; // free memory
                super.endRDF();
            }

        };
    }

    /**
     * Callback interface called by the engine each time a rule is triggered.
     */
    public interface Callback {

        /**
         * Callback called each time a rule is triggered, i.e., its WHERE expression is matched on
         * input statements producing certain bindings.
         *
         * @param deleteHandler
         *            the handler where to send statements that have to be deleted by the engine
         * @param insertHandler
         *            the handler where to send statements that have to be inserted by the engine
         * @param rule
         *            the rule triggered
         * @param bindings
         *            the bindings produced by the evaluation of the WHERE expression
         * @return true, if the engine should continue processing this rule activation, performing
         *         the requested deletions and insertions; false otherwise (in this case,
         *         deletions and insertions performed by the method through the two supplied
         *         handlers will still be applied)
         */
        boolean ruleTriggered(final RDFHandler deleteHandler, RDFHandler insertHandler,
                final Rule rule, final BindingSet bindings);

    }

}