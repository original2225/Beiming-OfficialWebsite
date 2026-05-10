package games.beiming.website.ops.config;

import games.beiming.website.ops.realtime.ContainerCreateWebSocketHandler;
import games.beiming.website.ops.realtime.ContainerTerminalWebSocketHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class OpsWebSocketConfig implements WebSocketConfigurer {
    private final ContainerTerminalWebSocketHandler terminalHandler;
    private final ContainerCreateWebSocketHandler createHandler;
    private final String frontendOrigin;

    public OpsWebSocketConfig(
        ContainerTerminalWebSocketHandler terminalHandler,
        ContainerCreateWebSocketHandler createHandler,
        @Value("${beiming.ops.frontend-origin}") String frontendOrigin
    ) {
        this.terminalHandler = terminalHandler;
        this.createHandler = createHandler;
        this.frontendOrigin = frontendOrigin;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(terminalHandler, "/ws/container-terminal")
            .setAllowedOriginPatterns(frontendOrigin, "http://127.0.0.1:*", "http://localhost:*");
        registry.addHandler(createHandler, "/ws/container-create")
            .setAllowedOriginPatterns(frontendOrigin, "http://127.0.0.1:*", "http://localhost:*");
    }
}
