# Test Jar

This directory contains a simple Clojure project to test the usage of Datalevin
release artifacts in these scenarios:

## Using Datalevin libary as a dependency in JVM Clojure project

Done with `test.sh`.

## Running Datalevin standalone uberjar

Done with `test-uber.sh`.

## Compile Clojure project into native image with Datalein libary as a dependency

Done with `test-native.sh`.

Here are some tips for native image compilation:

By default, all classes are initialized at run time. This is good, because we
want to avoid starting things that should not be started at native image build
time, e.g., openning Datalevin databases, starting Datalevin background threads,
and so on.

However, Clojure namespaces themselves need to be initialized at build time for
various reasons, e.g. they generate classes with random names that are not known
ahead of time, violating the native image's Closed World assumption. A library
[`clj-easy.graal-build-time`](https://github.com/clj-easy/graal-build-time) can
help with that, so our `deps.edn` has it as a dependency, and our native image
command line has the following argument:

     --features=clj_easy.graal_build_time.InitClojureClasses

Notice also that the main namespace with the `-main` function, `test-jar.core`,
is using `(:gen-class)`, so that this namespace is compiled ahead of time for
the native image compiler to find the application entry point.
