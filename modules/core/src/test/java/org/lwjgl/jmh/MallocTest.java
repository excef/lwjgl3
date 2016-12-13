/*
 * Copyright LWJGL. All rights reserved.
 * License terms: http://lwjgl.org/license.php
 */
package org.lwjgl.jmh;

import org.lwjgl.BufferUtils;
import org.lwjgl.system.jemalloc.JEmalloc;
import org.lwjgl.system.libc.LibCStdlib;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Threads;

import java.nio.ByteBuffer;

import static org.lwjgl.system.jemalloc.JEmacros.*;

/**
 * Windows, Sandy Bridge 3.1GHz, JDK 8u77
 *
 * Threads = 1
 * Benchmark                    Mode  Cnt    Score   Error  Units
 * MallocTest.nio               avgt    5  247,508 � 3,249  ns/op
 * MallocTest.calloc            avgt    5   92,208 � 0,473  ns/op
 * MallocTest.je_calloc         avgt    5   62,399 � 0,456  ns/op
 *
 * MallocTest.malloc            avgt    5   93,504 � 0,747  ns/op
 * MallocTest.je_malloc         avgt    5   53,392 � 0,354  ns/op
 *
 * MallocTest.aligned_alloc     avgt    5   87,125 � 0,627  ns/op
 * MallocTest.je_aligned_alloc  avgt    5   64,990 � 0,290  ns/op
 * MallocTest.je_mallocx        avgt    5   76,573 � 0,360  ns/op
 *
 * Threads = 8
 * Benchmark                    Mode  Cnt     Score     Error  Units
 * MallocTest.nio               avgt    5  2557,198 � 521,399  ns/op
 * MallocTest.calloc            avgt    5   234,454 �  49,934  ns/op
 * MallocTest.je_calloc         avgt    5   128,828 �   1,531  ns/op
 *
 * MallocTest.malloc            avgt    5   258,318 �  46,083  ns/op
 * MallocTest.je_malloc         avgt    5   110,178 �   1,761  ns/op
 *
 * MallocTest.aligned_alloc     avgt    5   226,646 �  18,097  ns/op
 * MallocTest.je_aligned_alloc  avgt    5   135,479 �   3,327  ns/op
 * MallocTest.je_mallocx        avgt    5   152,831 �  11,255  ns/op
 */
@Threads(8)
public class MallocTest {

	private static final int SIZE = 128;

	private static final int ALIGNMENT = 32;

	private static final int MALLOCX_FLAGS = MALLOCX_ZERO | MALLOCX_ALIGN(ALIGNMENT);

	@Benchmark
	public void nio() {
		ByteBuffer mem = BufferUtils.createByteBuffer(SIZE);
		((sun.nio.ch.DirectBuffer)mem).cleaner().clean();
	}

	@Benchmark
	public void malloc() {
		ByteBuffer mem = LibCStdlib.malloc(SIZE);
		LibCStdlib.free(mem);
	}

	@Benchmark
	public void calloc() {
		ByteBuffer mem = LibCStdlib.calloc(1, SIZE);
		LibCStdlib.free(mem);
	}

	@Benchmark
	public void aligned_alloc() {
		ByteBuffer mem = LibCStdlib.aligned_alloc(ALIGNMENT, SIZE);
		LibCStdlib.aligned_free(mem);
	}

	@Benchmark
	public void je_malloc() {
		ByteBuffer mem = JEmalloc.je_malloc(SIZE);
		JEmalloc.je_free(mem);
	}

	@Benchmark
	public void je_calloc() {
		ByteBuffer mem = JEmalloc.je_calloc(1, SIZE);
		JEmalloc.je_free(mem);
	}

	@Benchmark
	public void je_aligned_alloc() {
		ByteBuffer mem = JEmalloc.je_aligned_alloc(ALIGNMENT, SIZE);
		JEmalloc.je_free(mem);
	}

	@Benchmark
	public void je_mallocx() {
		ByteBuffer mem = JEmalloc.je_mallocx(SIZE, MALLOCX_FLAGS);
		JEmalloc.je_free(mem);
	}

}
