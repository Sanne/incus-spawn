package dev.incusspawn.util;

import java.util.Collection;
import java.util.List;

/**
 * An {@link Output} that passes every method on to {@link #delegate()}: the one list of them a
 * decorator overrides from, so a method {@code Output} gains is forwarded by adding it here once,
 * never falling through to a default that drops it ({@code OutputBindingTest}).
 */
abstract class ForwardingOutput implements Output {

    /** Where each message goes. */
    abstract Output delegate();

    @Override public List<Output> destinations() { return delegate().destinations(); }
    @Override public void warn(String msg, String... details) { delegate().warn(msg, details); }
    @Override public void error(String msg, String... details) { delegate().error(msg, details); }
    @Override public void warnAside(String msg) { delegate().warnAside(msg); }
    @Override public void stepWarn(String msg) { delegate().stepWarn(msg); }
    @Override public void warnBanner(String title, String... lines) { delegate().warnBanner(title, lines); }
    @Override public void stepFail(String msg) { delegate().stepFail(msg); }
    @Override public void note(String msg) { delegate().note(msg); }
    @Override public void header(String msg, String detail) { delegate().header(msg, detail); }
    @Override public void buildHeader(String name, int index, int total) { delegate().buildHeader(name, index, total); }
    @Override public void branchHeader(String name, String source) { delegate().branchHeader(name, source); }
    @Override public void section(String msg) { delegate().section(msg); }
    @Override public Group group(String title, String detail) { return delegate().group(title, detail); }
    @Override public String indent() { return delegate().indent(); }
    @Override public void step(String msg) { delegate().step(msg); }
    @Override public java.io.PrintStream results() { return delegate().results(); }
    @Override public void frame(String... lines) { delegate().frame(lines); }
    @Override public void gap() { delegate().gap(); }
    @Override public void prompt(String question) { delegate().prompt(question); }
    @Override public void windowTitle(String title) { delegate().windowTitle(title); }
    @Override public void stepNote(String msg) { delegate().stepNote(msg); }
    @Override public void failureDetail(String msg) { delegate().failureDetail(msg); }
    @Override public void ok(String msg) { delegate().ok(msg); }
    @Override public void success(String msg) { delegate().success(msg); }
    @Override public void list(Collection<String> items) { delegate().list(items); }
    @Override public void stepStart(String msg) { delegate().stepStart(msg); }
    @Override public void stepProgress(java.util.function.Supplier<String> detail) { delegate().stepProgress(detail); }
    @Override public void stepDone(String detail) { delegate().stepDone(detail); }
    @Override public void stepBreak() { delegate().stepBreak(); }
    @Override public void releaseTerminal() { delegate().releaseTerminal(); }
    @Override public void abandonStep() { delegate().abandonStep(); }
}
