package za.co.claims.processing.client;

import org.springframework.stereotype.Component;
import za.co.claims.processing.exception.DownstreamTechnicalException;

@Component
public class DummyClientRegistryClient implements ClientRegistryClient {

    @Override
    public boolean isClientValid(String clientId) {
        if ("CLIENT_ERROR".equalsIgnoreCase(clientId)) {
            throw new DownstreamTechnicalException("Client Registry is temporarily unavailable");
        }
        return !"INVALID_CLIENT".equalsIgnoreCase(clientId);
    }
}
