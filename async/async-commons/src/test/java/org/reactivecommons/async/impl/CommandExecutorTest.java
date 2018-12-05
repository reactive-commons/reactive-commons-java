package org.reactivecommons.async.impl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.reactivecommons.api.domain.Command;
import org.reactivecommons.async.api.handlers.CommandHandler;
import org.reactivecommons.async.impl.communications.Message;

import java.util.function.Function;

@RunWith(MockitoJUnitRunner.class)
public class CommandExecutorTest {

    @Mock
    private CommandHandler<Object> eventHandler;

    @Mock
    private Function<Message, Command<Object>> converter;


    @InjectMocks
    private CommandExecutor<Object> executor;

    @Test
    public void execute() {
//        Message message = new RabbitMessage();
//        executor.execute(message)
    }
}