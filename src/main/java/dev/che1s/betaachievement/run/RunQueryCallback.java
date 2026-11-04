package dev.che1s.betaachievement.run;

public interface RunQueryCallback<T> {
    void onResult(T result);
    void onFailure(Exception exception);
}
