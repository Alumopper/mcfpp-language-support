package top.mcfpp.intellij.project;

import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.projectRoots.ProjectJdkTable;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import java.nio.file.Path;

public final class McfppSdkImportTest extends BasePlatformTestCase {
    public void testRepeatedSyncReusesSdkWithDifferentPathSeparators() {
        var table=ProjectJdkTable.getInstance();
        var before=java.util.Set.of(table.getAllJdks());
        try {
            WriteAction.run(() -> {
                String path=System.getProperty("java.home");
                var first=McfppProjectService.sdkForJavaHome(Path.of(path.replace('\\','/')));
                var second=McfppProjectService.sdkForJavaHome(Path.of(path.replace('/',java.io.File.separatorChar)));
                assertSame(first,second);
            });
        } finally {
            WriteAction.run(() -> { for(var sdk:table.getAllJdks()) if(!before.contains(sdk)) table.removeJdk(sdk); });
        }
    }
}
