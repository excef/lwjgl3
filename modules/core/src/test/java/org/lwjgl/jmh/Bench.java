/*
 * Copyright LWJGL. All rights reserved.
 * License terms: http://lwjgl.org/license.php
 */
package org.lwjgl.jmh;

import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.concurrent.TimeUnit;

public final class Bench {

	private Bench() {
	}

	// Run with:
	//     ant demo -Dclass=org.lwjgl.jmh.Bench -Dargs=<regex>
	public static void main(String[] args) throws RunnerException {
		if ( args.length == 0 )
			throw new IllegalArgumentException("Please specify the benchmark include regex.");

		Options opt = new OptionsBuilder()
			.include(args[0])
			.forks(1)
			//.addProfiler(WinPerfAsmProfiler.class)
			.warmupIterations(3)
			.measurementIterations(5)
			.mode(Mode.AverageTime)
			.timeUnit(TimeUnit.NANOSECONDS)
			.jvmArgsPrepend("-server")
			.build();

		new Runner(opt).run();
	}

}