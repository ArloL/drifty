const std = @import("std");

pub fn build(b: *std.Build) void {
    const target = b.standardTargetOptions(.{});
    const optimize = b.standardOptimizeOption(.{});

    // Optional: link system libsodium for secret encryption in --fix mode.
    // Requires libsodium-dev (apt) or libsodium (brew) to be installed.
    // Without this flag the binary compiles fine; the encrypt function is
    // compiled out and --fix will skip secret updates.
    const use_sodium = b.option(bool, "sodium", "Enable libsodium crypto support") orelse false;
    const build_opts = b.addOptions();
    build_opts.addOption(bool, "use_sodium", use_sodium);

    const exe = b.addExecutable(.{
        .name = "drifty",
        .root_source_file = b.path("src/main.zig"),
        .target = target,
        .optimize = optimize,
    });

    exe.root_module.addOptions("build_options", build_opts);

    if (use_sodium) {
        exe.linkSystemLibrary("sodium");
        exe.linkLibC();
    }

    b.installArtifact(exe);

    const run_cmd = b.addRunArtifact(exe);
    run_cmd.step.dependOn(b.getInstallStep());
    if (b.args) |args| run_cmd.addArgs(args);

    const run_step = b.step("run", "Run drifty");
    run_step.dependOn(&run_cmd.step);
}
