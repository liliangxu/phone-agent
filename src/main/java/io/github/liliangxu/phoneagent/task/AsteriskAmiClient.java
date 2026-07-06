package io.github.liliangxu.phoneagent.task;

public interface AsteriskAmiClient {
    void setInUse(int slot);

    void setNotInUse(int slot);

    void originateRingPhone();
}
