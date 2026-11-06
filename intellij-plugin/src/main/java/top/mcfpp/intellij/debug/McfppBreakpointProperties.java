package top.mcfpp.intellij.debug;

import com.intellij.xdebugger.breakpoints.XBreakpointProperties;

public final class McfppBreakpointProperties extends XBreakpointProperties<McfppBreakpointProperties> {
    @Override public McfppBreakpointProperties getState() { return this; }
    @Override public void loadState(McfppBreakpointProperties state) {}
}
