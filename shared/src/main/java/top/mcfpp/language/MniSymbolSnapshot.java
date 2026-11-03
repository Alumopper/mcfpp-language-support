package top.mcfpp.language;
import java.util.List;
/** JSON-safe editor metadata; no provider code is loaded or invoked to build it. */
public final class MniSymbolSnapshot {
    public String projectUri;
    public long generation;
    public List<Symbol> symbols=List.of();
    public static final class Symbol {
        public String namespace,name,typeName,detail,uri,documentation;
        public int line,character,endLine,endCharacter;
        public List<Parameter> parameters=List.of();
    }
    public static final class Parameter {
        public String name,typeName,mode;
    }
}

