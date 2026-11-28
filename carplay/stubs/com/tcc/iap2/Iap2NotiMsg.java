package com.tcc.iap2;

import java.util.ArrayList;
import java.util.List;

/** An iAP2 message: a group of items (id, type, values). As on the car, a getter of the wrong type gives null. */
public class Iap2NotiMsg {
    final Iap2ObjGroup group = new Iap2ObjGroup();

    public Iap2ObjGroup getItemGroup() {
        return group;
    }

    public class Iap2ObjGroup {
        final List<Iap2ObjItem> items = new ArrayList<Iap2ObjItem>();

        public int getItemCnt() {
            return items.size();
        }

        public Iap2ObjItem getObjItem(int index) {
            return items.get(index);
        }

        /** off-car tests: add an item (values: int[], long[], String[], byte[] or Iap2ObjGroup[]) */
        public Iap2ObjGroup add(int id, int type, Object values) {
            items.add(new Iap2ObjItem(id, type, values));
            return this;
        }
    }

    public class Iap2ObjItem {
        final int id, type;
        final Object values;

        Iap2ObjItem(int id, int type, Object values) {
            this.id = id;
            this.type = type;
            this.values = values;
        }

        public int getItemId() {
            return id;
        }

        public int getItemParamType() {
            return type;
        }

        public int getItemObjCount() {
            return values == null ? 0 : java.lang.reflect.Array.getLength(values);
        }

        public int[] getItemIntArray() {
            return values instanceof int[] ? (int[]) values : null;
        }

        public long[] getItemLongArray() {
            return values instanceof long[] ? (long[]) values : null;
        }

        public String[] getItemStringArray() {
            return values instanceof String[] ? (String[]) values : null;
        }

        public byte[] getItemByteArray() {
            return values instanceof byte[] ? (byte[]) values : null;
        }

        public Iap2ObjGroup[] getItemGroupArray() {
            return values instanceof Iap2ObjGroup[] ? (Iap2ObjGroup[]) values : null;
        }
    }
}
