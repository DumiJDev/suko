package io.suko.ext;

public interface ExtensionContext {
    void target(Target target);

    void vocabulary(Vocabulary vocabulary);

    void checker(Checker checker);
}
