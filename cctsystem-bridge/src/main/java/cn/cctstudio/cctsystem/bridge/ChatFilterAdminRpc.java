package cn.cctstudio.cctsystem.bridge;

import cn.cctstudio.cctsystem.core.filter.ReloadingChatFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

public final class ChatFilterAdminRpc {
    private static final ObjectMapper JSON = new ObjectMapper();
    private ChatFilterAdminRpc() {}
    public static void register(BridgeRpcRouter router, Supplier<ReloadingChatFilter> rules, Executor executor) {
        for (String operation : new String[]{"admin.chat-filter.document", "admin.chat-filter.save"}) {
            boolean write = operation.endsWith(".save");
            router.registerCall(operation, call -> {
                String group = call.payload().path("actorGroup").asText("");
                if (!(write ? Set.of("owner","admin") : Set.of("owner","admin","mod")).contains(group))
                    throw new RpcHandlingException("ADMIN_FORBIDDEN","Permission denied",false);
                return CompletableFuture.supplyAsync(() -> {
                    try {
                        var filter = rules.get();
                        var document = write ? filter.saveDocument(call.payload().path("content").asText(null),
                            call.payload().path("revision").asText("")) : filter.document();
                        return JSON.createObjectNode().put("content",document.content()).put("revision",document.revision());
                    } catch (IllegalArgumentException failure) {
                        throw new RpcHandlingException("FILTER_DOCUMENT_INVALID",failure.getMessage(),false);
                    } catch (java.io.IOException failure) {
                        throw new RpcHandlingException("FILTER_DOCUMENT_UNAVAILABLE","Unable to read or save filter rules",false);
                    }
                },executor);
            });
        }
    }
}
