package org.reactivecommons.async.api;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.reactivecommons.async.api.handlers.CommandHandler;
import org.reactivecommons.async.api.handlers.EventHandler;
import org.reactivecommons.async.api.handlers.QueryHandler;

import java.lang.reflect.ParameterizedType;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Getter
@NoArgsConstructor(access = AccessLevel.PACKAGE)
public class HandlerRegistry {

    public static HandlerRegistry register(){
        return new HandlerRegistry();
    }

    private final List<RegisteredQueryHandler<?, ?>> handlers = new CopyOnWriteArrayList<>();
    private final List<RegisteredEventListener> eventListeners = new CopyOnWriteArrayList<>();
    private final List<RegisteredCommandHandler> commandHandlers = new CopyOnWriteArrayList<>();

    public <T> HandlerRegistry listenEvent(String eventName, EventHandler<T> fn, Class<T> eventClass){
        eventListeners.add(new RegisteredEventListener<>(eventName, fn,  eventClass));
        return this;
    }

    public <T> HandlerRegistry handleCommand(String commandName, CommandHandler<T> fn, Class<T> commandClass){
        commandHandlers.add(new RegisteredCommandHandler<>(commandName, fn,  commandClass));
        return this;
    }

    @SuppressWarnings("unchecked")
    public <T, R> HandlerRegistry serveQuery(String commandId, QueryHandler<T, R> handler){
        return serveQuery(commandId, handler, inferGenericParameterType(handler));
    }

    @SuppressWarnings("unchecked")
    private <T, R> Class<R> inferGenericParameterType(QueryHandler<T, R> handler){
        try{
            ParameterizedType genericSuperclass = (ParameterizedType) handler.getClass().getGenericInterfaces()[0];
            return  (Class<R>) genericSuperclass.getActualTypeArguments()[1];
        }catch (Exception e){
            throw new RuntimeException("Fail to infer generic Query class, please use serveQuery(path, handler, class) instead");
        }
    }

    public <T, R> HandlerRegistry serveQuery(String commandId, QueryHandler<T, R> handler, Class<R> queryClass){
        handlers.add(new RegisteredQueryHandler<>(commandId, handler, queryClass));
        return this;
    }


    @RequiredArgsConstructor
    @Getter
    public static class RegisteredQueryHandler<T, R> {
        private final String path;
        private final QueryHandler<T, R> handler;
        private final Class<R> queryClass;
    }

    @RequiredArgsConstructor
    @Getter
    public static class RegisteredEventListener<T> {
        private final String path;
        private final EventHandler<T> handler;
        private final Class<T> inputClass;
    }

    @RequiredArgsConstructor
    @Getter
    public static class RegisteredCommandHandler<T> {
        private final String path;
        private final CommandHandler<T> handler;
        private final Class<T> inputClass;
    }

}



