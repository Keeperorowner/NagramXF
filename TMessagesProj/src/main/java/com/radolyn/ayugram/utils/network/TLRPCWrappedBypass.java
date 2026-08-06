package com.radolyn.ayugram.utils.network;

import org.telegram.tgnet.InputSerializedData;
import org.telegram.tgnet.OutputSerializedData;
import org.telegram.tgnet.TLObject;

public class TLRPCWrappedBypass extends TLObject {
    public final TLObject inner;

    public TLRPCWrappedBypass(TLObject inner) {
        this.inner = inner;
    }

    @Override
    public void readParams(InputSerializedData stream, boolean exception) {
        if (inner != null) {
            inner.readParams(stream, exception);
        }
    }

    @Override
    public void serializeToStream(OutputSerializedData stream) {
        if (inner != null) {
            inner.serializeToStream(stream);
        }
    }

    @Override
    public TLObject deserializeResponse(InputSerializedData stream, int constructor, boolean exception) {
        if (inner != null) {
            return inner.deserializeResponse(stream, constructor, exception);
        }
        return null;
    }

    @Override
    public void freeResources() {
        if (inner != null) {
            inner.freeResources();
        }
    }

    @Override
    public int getObjectSize() {
        if (inner != null) {
            return inner.getObjectSize();
        }
        return super.getObjectSize();
    }
}
