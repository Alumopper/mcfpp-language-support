package example.mni;

import top.mcfpp.annotations.MNIFunction;
import top.mcfpp.core.lang.MCInt;
import top.mcfpp.util.ValueWrapper;

public final class Example {
    public static int answerValue() { return 42; }

    @MNIFunction(returnType = "int")
    public static void answer(ValueWrapper<MCInt> result) {
        result.set(new MCInt(answerValue(), "answer"));
    }
}
