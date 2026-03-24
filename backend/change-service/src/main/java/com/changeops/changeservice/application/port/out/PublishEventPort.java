package com.changeops.changeservice.application.port.out;

public interface PublishEventPort {
    void publish(Object domainEvent);
}
