licenses(["notice"])  # Apache 2.0

load(
    "//aspect/testing/rules:intellij_aspect_test_fixture.bzl",
    "intellij_aspect_test_fixture",
)
load(
    "@io_bazel_rules_scala//scala:scala.bzl",
    "scala_test",
)

scala_test(
    name = "FooTest",
    size = "large",
    srcs = ["FooTest.scala"],
)

intellij_aspect_test_fixture(
    name = "footest_fixture",
    deps = [":FooTest"],
)

java_test(
    name = "ScalaTestTest",
    srcs = ["ScalaTestTest.java"],
    data = [":footest_fixture"],
    deps = [
        "//aspect/testing:BazelIntellijAspectTest",
        "//aspect/testing:guava",
        "//aspect/testing/rules:IntellijAspectTest",
        "//aspect/testing/rules:intellij_aspect_test_fixture_java_proto",
        "//proto:intellij_ide_info_java_proto",
        "@junit//jar",
        "@truth//jar",
    ],
)
