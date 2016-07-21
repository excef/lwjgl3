/*
 * Copyright LWJGL. All rights reserved.
 * License terms: http://lwjgl.org/license.php
 */
package org.lwjgl.jmh;

import org.lwjgl.system.MemoryAccess;
import org.openjdk.jmh.annotations.*;
import sun.misc.Unsafe;

import java.nio.ByteOrder;
import java.util.Arrays;

import static org.lwjgl.system.MemoryUtil.*;
import static org.lwjgl.system.libc.LibCString.*;

@State(Scope.Benchmark)
public class MemSetTest {

	private static final Unsafe UNSAFE = MemoryAccess.getUnsafeInstance();

	private static final int BUFFER_SIZE = 128 * 1024;

	private static final long m = nmemAlignedAlloc(64, BUFFER_SIZE);

	private static final byte[] a = new byte[BUFFER_SIZE];

	// The Unsafe loop is faster for small sizes.
	//@Param({ "16", "32", "64", "128", "256", "1024" })
	// libc is faster for bigger sizes
	@Param({ "1024", "4096", "8192", "16384", "131072" })
	public int length;

	/*@Benchmark
	public void offheap_LWJGL() {
		memSet(m, 0, length);
	}*/

	@Benchmark
	public void offheap_baseline() {
		// Slower on all sizes
		UNSAFE.setMemory(null, m, length, (byte)0);
	}

	@Benchmark
	public void offheap_java() {
		// Fastest on small sizes
		memSetLoop(m, (byte)0, length);
	}

	@Benchmark
	public void offheap_libc() {
		// Fastest on big sizes
		nmemset(m, 0, length);
	}

	@Benchmark
	public void array_baseline() {
		Arrays.fill(a, 0, length, (byte)0);
	}

	@Benchmark
	public void array_libc() {
		// Same as offheap_libc with JNI Critical Natives
		nmemset(a, 0, length);
	}

	private static void memSetLoop(long dst, byte value, int bytes) {
		int i = 0;

		int misalignment = (int)dst & 7;
		long fill = fill(value);

		if ( 8 <= bytes ) {
			if ( misalignment != 0 ) {
				memPutLong(dst - misalignment, merge(memGetLong(dst - misalignment), fill, shr(-1L, misalignment)));
				i += 8 - misalignment;
			}

			// Aligned longs for performance
			for ( ; i <= bytes - 8; i += 8 )
				memPutLong(dst + i, fill);
		} else if ( misalignment != 0 && 0 < bytes ) {
			memPutLong(dst - misalignment, merge(memGetLong(dst - misalignment), fill, shr(shl(-1L, 8 - bytes), misalignment)));
			i += 8 - misalignment;
		}

		// Aligned tail
		if ( i < bytes )
			memPutLong(dst + i, merge(memGetLong(dst + i), fill, shl(-1L, 8 - (bytes - i))));
	}

	private static long shl(long value, int bytes) {
		if ( ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN )
			return value << (bytes << 3);
		else
			return value >>> (bytes << 3);
	}

	private static long shr(long value, int bytes) {
		if ( ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN )
			return value >>> (bytes << 3);
		else
			return value << (bytes << 3);
	}

	private static long merge(long a, long b, long mask) {
		return a ^ ((a ^ b) & mask);
	}

	private static long fill(byte value) {
		long fill = value;

		if ( value != 0 ) {
			fill |= fill << 8;
			fill |= fill << 16;
			fill |= fill << 32;
		}

		return fill;
	}

}