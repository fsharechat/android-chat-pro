package cn.wildfire.chat.kit.net;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

public class ServerHostSpecTest {

    private static final ServerNode SH = new ServerNode("上海节点", "sh.example.com");
    private static final ServerNode MAIN = new ServerNode("主节点", "main.example.com");
    private static final ServerNode S2 = new ServerNode("备节点 S2", "s2.example.com");

    private List<ServerNode> nodes() {
        return Arrays.asList(SH, MAIN, S2);
    }

    @Test
    public void joinAllUsesColonSeparatorInDeclaredOrder() {
        assertEquals("sh.example.com:main.example.com:s2.example.com",
                ServerHostSpec.joinAll(nodes()));
    }

    @Test
    public void joinAllOfSingleNodeHasNoSeparator() {
        assertEquals("sh.example.com", ServerHostSpec.joinAll(Collections.singletonList(SH)));
    }

    @Test
    public void resolveWithoutPinReturnsAllHosts() {
        assertEquals("sh.example.com:main.example.com:s2.example.com",
                ServerHostSpec.resolve(nodes(), null));
    }

    @Test
    public void resolveWithBlankPinReturnsAllHosts() {
        assertEquals("sh.example.com:main.example.com:s2.example.com",
                ServerHostSpec.resolve(nodes(), "   "));
    }

    @Test
    public void resolveWithPinReturnsThatHostOnly() {
        assertEquals("main.example.com", ServerHostSpec.resolve(nodes(), "main.example.com"));
    }

    @Test
    public void resolveWithUnknownPinFallsBackToAllHosts() {
        assertEquals("sh.example.com:main.example.com:s2.example.com",
                ServerHostSpec.resolve(nodes(), "gone.example.com"));
    }

    @Test
    public void findNodeReturnsMatchingNode() {
        assertSame(S2, ServerHostSpec.findNode(nodes(), "s2.example.com"));
    }

    @Test
    public void findNodeReturnsNullForUnknownHost() {
        assertNull(ServerHostSpec.findNode(nodes(), "gone.example.com"));
    }

    @Test
    public void findNodeReturnsNullForNullHost() {
        assertNull(ServerHostSpec.findNode(nodes(), null));
    }

    @Test
    public void joinAllOfEmptyListReturnsEmptyString() {
        assertEquals("", ServerHostSpec.joinAll(new ArrayList<ServerNode>()));
    }
}
