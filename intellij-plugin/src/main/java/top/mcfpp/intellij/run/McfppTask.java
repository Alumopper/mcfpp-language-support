package top.mcfpp.intellij.run;
public enum McfppTask {
    BUILD("Build","build"), RUN("Run","build"), INSTALL("Install","install"), UPDATE("Update","update"), CLEAN("Clean","clean"),
    TEST("Test","test"), LINT("Lint","--check-lint"), FORMAT_CHECK("Format Check","--check-format"), DUMP_IR("Dump IR","build");
    public final String title,command;
    McfppTask(String title,String command) { this.title=title; this.command=command; }
    @Override public String toString() { return title; }
    public boolean compilesSources() { return this==BUILD || this==RUN || this==TEST || this==DUMP_IR; }
}

