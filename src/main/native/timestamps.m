#import <QuartzCore/QuartzCore.h>

#include "m2m_context.h"

// GPU timestamps in nanoseconds of host time (the CACurrentMediaTime clock). Apple GPUs only sample counters at
// encoder boundaries, so a timestamp splits the command buffer instead: its value is when the GPU finished all work
// before it, which is what a GL timestamp query reports too.
@interface M2MTimestampPool : NSObject
@property(nonatomic, strong) NSMutableData *values;
@property(nonatomic) int64_t size;
@end

@implementation M2MTimestampPool
@end

// Not yet written, or written but not finished on the GPU.
static const int64_t M2M_TIMESTAMP_PENDING = -1;

int64_t m2m_timestamp_pool_create(int64_t size) {
	M2MTimestampPool *pool = [M2MTimestampPool new];
	pool.size = size;
	pool.values = [NSMutableData dataWithLength:(NSUInteger)size * sizeof(int64_t)];
	int64_t *values = pool.values.mutableBytes;
	for (int64_t i = 0; i < size; i++) {
		values[i] = M2M_TIMESTAMP_PENDING;
	}
	return m2m_retain(pool);
}

// Fills out[0..count) with values from first on; -1 where a value is not available yet.
void m2m_timestamp_pool_read(int64_t handle, int64_t first, int64_t count, int64_t *out) {
	M2MTimestampPool *pool = m2m_borrow(handle);
	@synchronized(pool) {
		const int64_t *values = pool.values.bytes;
		for (int64_t i = 0; i < count; i++) {
			out[i] = values[first + i];
		}
	}
}

static void m2m_timestamp_store(M2MTimestampPool *pool, int64_t index, int64_t value) {
	@synchronized(pool) {
		((int64_t *)pool.values.mutableBytes)[index] = value;
	}
}

void m2m_timestamp_write(int64_t ctx_handle, int64_t pool_handle, int64_t index) {
	@autoreleasepool {
		M2MContext *ctx = m2m_ctx(ctx_handle);
		M2MTimestampPool *pool = m2m_borrow(pool_handle);
		m2m_timestamp_store(pool, index, M2M_TIMESTAMP_PENDING);

		// Committed outside m2m_context_commit: it does not get a submit index of its own, since the frame's
		// buffer committed after it on the same queue completes later and covers it.
		__weak M2MContext *weakCtx = ctx;
		m2m_commit_open(ctx, @"mine2metal timestamp", ^(CFTimeInterval gpuStart, CFTimeInterval gpuEnd, NSError *failure) {
			M2MContext *strongCtx = weakCtx;
			if (failure) {
				m2m_record_error(strongCtx, failure);
			}
			// An empty command buffer may report no GPU times; completion is then the best estimate.
			CFTimeInterval end = gpuEnd > 0 ? gpuEnd : CACurrentMediaTime();
			strongCtx.gpuSeconds += gpuEnd - gpuStart;
			m2m_timestamp_store(pool, index, (int64_t)(end * 1e9));
		});
	}
}

int64_t m2m_host_time_nanos(void) {
	return (int64_t)(CACurrentMediaTime() * 1e9);
}
