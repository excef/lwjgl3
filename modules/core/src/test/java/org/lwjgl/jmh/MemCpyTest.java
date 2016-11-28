/*
 * Copyright LWJGL. All rights reserved.
 * License terms: http://lwjgl.org/license.php
 */
package org.lwjgl.jmh;

import org.lwjgl.system.MemoryAccess;
import org.openjdk.jmh.annotations.*;
import sun.misc.Unsafe;

import java.nio.ByteOrder;

import static org.lwjgl.system.MemoryUtil.*;
import static org.lwjgl.system.libc.LibCString.*;

@State(Scope.Benchmark)
public class MemCpyTest {

	private static final Unsafe UNSAFE = MemoryAccess.getUnsafeInstance();

	private static final int BUFFER_SIZE = 128 * 1024;

	private static final long f = nmemAlloc(BUFFER_SIZE);
	private static final long t = nmemAlloc(BUFFER_SIZE);

	private static final byte[] a = new byte[BUFFER_SIZE];
	private static final byte[] b = new byte[BUFFER_SIZE];

	//@Param({ "16", "32", "64", "128", "256", "384", "512" })
	@Param({ "1024", "4096", "8192", "16384", "131072" })
	public int length;

	/*@Benchmark
	public void offheap_LWJGL() {
		memCopy(f, t, length);
	}*/

	//@Benchmark
	public void offheap_baseline() {
		// Fastest on small sizes
		UNSAFE.copyMemory(null, f, null, t, length);
	}

	//@Benchmark
	public void offheap_java() {
		// Fastest on very small sizes
		memCopyAligned(f, t, length);
	}

	@Benchmark
	public void offheap_libc() {
		// Fastest on big sizes
		nmemcpy(t, f, length);
	}

	@Benchmark
	public void array_baseline() {
		System.arraycopy(a, 0, b, 0, length);
	}

	@Benchmark
	public void array_libc() {
		// Slower than offheap_libc but faster than array_baseline with JNI Critical Natives
		nmemcpy(b, a, length);
	}

	private static void memCopyAligned(long src, long dst, int bytes) {
		int i = 0;

		// Aligned longs for performance
		for ( ; i <= bytes - 8; i += 8 )
			memPutLong(dst + i, memGetLong(src + i));

		// Aligned tail
		if ( i < bytes )
			memPutLong(dst + i, merge(memGetLong(dst + i), memGetLong(src + i), shl(-1L, 8 - (bytes - i))));
	}

	private static long shl(long value, int bytes) {
		if ( ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN )
			return value << (bytes << 3);
		else
			return value >>> (bytes << 3);
	}

	private static long merge(long a, long b, long mask) {
		return a ^ ((a ^ b) & mask);
	}

}
