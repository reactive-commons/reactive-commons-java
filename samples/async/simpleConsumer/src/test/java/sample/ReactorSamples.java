package sample;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

@RequiredArgsConstructor
public class ReactorSamples {

    private final UserRepository repository;
    private final UserCache userCache;

    public Mono<User> findUser(String id) {
        return repository.findUser(id).onErrorResume((e) -> userCache.getFromCache(id));
    }

    public Mono<User> findUser2(String id) {
        return repository.findUser(id)
            .onErrorResume((e) -> userCache.getFromCache(id));
    }

    public Mono<User> findUser3(String id) {
        findUser(id).subscribe();
        final Mono<User> user = repository.findUser(id);
        return user.onErrorResume((e) -> userCache.getFromCache(id));
    }


}

class User {
}

interface UserRepository {
    Mono<User> findUser(String id);
}

interface UserCache {
    Mono<User> getFromCache(String id);
}