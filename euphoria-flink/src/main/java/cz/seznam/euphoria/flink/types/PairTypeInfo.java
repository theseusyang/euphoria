package cz.seznam.euphoria.flink.types;

import cz.seznam.euphoria.core.client.util.Pair;
import org.apache.flink.api.common.ExecutionConfig;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.common.typeutils.CompositeType;
import org.apache.flink.api.common.typeutils.TypeComparator;
import org.apache.flink.api.common.typeutils.TypeSerializer;
import org.apache.flink.api.java.typeutils.runtime.TupleComparatorBase;
import org.apache.flink.core.memory.DataInputView;
import org.apache.flink.core.memory.DataOutputView;
import org.apache.flink.core.memory.MemorySegment;
import org.apache.flink.types.KeyFieldOutOfBoundsException;
import org.apache.flink.types.NullFieldException;
import org.apache.flink.types.NullKeyFieldException;
import org.apache.flink.util.Preconditions;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static java.util.Objects.requireNonNull;

public class PairTypeInfo extends CompositeType<Pair> {

  private final TypeInformation<?>[] types;
  private final String [] fieldNames;
  private final int totalFields;

  public PairTypeInfo(TypeInformation<?> firstTypeInfo, TypeInformation<?> secondTypeInfo) {
    super(Pair.class);

    this.types = new TypeInformation<?>[]{
        requireNonNull(firstTypeInfo),
        requireNonNull(secondTypeInfo)
    };
    this.fieldNames = new String[] {"first", "second"};

    int totalFields = 0;
    for (TypeInformation<?> t : this.types) {
      totalFields += t.getTotalFields();
    }
    this.totalFields = totalFields;
  }

  @Override
  public boolean equals(Object obj) {
    if (obj instanceof PairTypeInfo) {
      PairTypeInfo other = (PairTypeInfo) obj;
      return other.canEqual(this)
          && Arrays.equals(this.types, other.types)
          && this.totalFields == other.totalFields;
    }
    return false;
  }

  @Override
  public boolean canEqual(Object obj) {
    return obj instanceof PairTypeInfo;
  }

  @Override
  public int hashCode() {
    return 31 * (31 * super.hashCode() + Arrays.hashCode(this.types)) + this.totalFields;
  }

  @Override
  public boolean isBasicType() {
    return false;
  }

  @Override
  public boolean isTupleType() {
    return false;
  }

  @Override
  public int getArity() {
    return this.types.length;
  }

  @Override
  public int getTotalFields() {
    return totalFields;
  }

  @Override
  public String[] getFieldNames() {
    return this.fieldNames;
  }

  @Override
  public int getFieldIndex(String fieldName) {
    for (int i = 0; i < this.fieldNames.length; i++) {
      if (this.fieldNames[i].equals(fieldName)) {
        return i;
      }
    }
    return -1;
  }

  @Override
  public <X> TypeInformation<X> getTypeAt(int pos) {
    if (pos < 0 || pos >= this.types.length) {
      throw new IndexOutOfBoundsException();
    }
    @SuppressWarnings("unchecked")
    TypeInformation<X> typed = (TypeInformation<X>) this.types[pos];
    return typed;
  }

  @Override
  public <X> TypeInformation<X> getTypeAt(String fieldExpression) {
    // XXX support nesting
    if ("first".equals(fieldExpression)){
      return getTypeAt(0);
    } else if ("second".equals(fieldExpression)) {
      return getTypeAt(1);
    } else {
      // XXX
      throw new UnsupportedOperationException("NOT IMPLEMENTED YET");
    }
  }

  @Override
  public void getFlatFields(String fieldExpression, int offset, List<FlatFieldDescriptor> result) {
    // XXX support nesting
    if ("first".equals(fieldExpression)) {
      result.add(new FlatFieldDescriptor(offset, getTypeAt(0)));
    } else if ("second".equals(fieldExpression)){
      result.add(new FlatFieldDescriptor(offset + getTypeAt(0).getTotalFields(), getTypeAt(1)));
    } else {
      // XXX
      throw new UnsupportedOperationException("NOT IMPLEMENTED YET");
    }
  }

  @Override
  protected TypeComparatorBuilder<Pair> createTypeComparatorBuilder() {
    return new PairTypeComparatorBuilder();
  }

  @SuppressWarnings("unchecked")
  @Override
  public TypeSerializer<Pair> createSerializer(ExecutionConfig config) {
    TypeSerializer<?>[] fieldSerializers = new TypeSerializer<?>[getArity()];
    for (int i = 0; i < types.length; i++) {
      fieldSerializers[i] = types[i].createSerializer(config);
    }
    return new PairSerializer(fieldSerializers);
  }

  private static class PairSerializer extends TypeSerializer<Pair> {
    private final TypeSerializer<Object>[] fieldSerializers;
    private int length = -2;

    @SuppressWarnings("unchecked")
    public PairSerializer(TypeSerializer<?>[] fieldSerializers) {
      if (fieldSerializers.length != 2) {
        throw new IllegalArgumentException("Pairs have exactly two field!");
      }
      this.fieldSerializers = (TypeSerializer<Object>[]) requireNonNull(fieldSerializers);
    }

    @Override
    public int hashCode() {
      return 31 * Arrays.hashCode(fieldSerializers);
    }

    @Override
    public boolean equals(Object obj) {
      if (obj instanceof PairSerializer) {
        PairSerializer other = (PairSerializer) obj;
        return other.canEqual(this)
                   && Arrays.equals(fieldSerializers, other.fieldSerializers);
      }
      return false;
    }

    @Override
    public boolean canEqual(Object obj) {
      return obj instanceof PairSerializer;
    }

    @Override
    public int getLength() {
      if (length == -2) {
        int sum = 0;
        for (TypeSerializer serializer : fieldSerializers) {
          if (serializer.getLength() > 0) {
            sum += serializer.getLength();
          } else {
            length = -1;
            return length;
          }
        }
        length = sum;
      }
      return length;
    }

    @Override
    public boolean isImmutableType() {
      return true; // ~ pair's themselves are immutable
    }

    @Override
    public TypeSerializer<Pair> duplicate() {
      boolean stateful = false;
      TypeSerializer<?>[] duplicateFieldSerializers = new TypeSerializer<?>[fieldSerializers.length];

      for (int i = 0; i < fieldSerializers.length; i++) {
        duplicateFieldSerializers[i] = fieldSerializers[i].duplicate();
        if (duplicateFieldSerializers[i] != fieldSerializers[i]) {
          // at least one of them is stateful
          stateful = true;
        }
      }

      if (stateful) {
        return new PairSerializer(duplicateFieldSerializers);
      } else {
        return this;
      }
    }

    // ~ helper to drop the generic information
    private TypeSerializer fieldSerializer(int fieldPos) {
      return fieldSerializers[fieldPos];
    }

    @Override
    public Pair createInstance() {
      return Pair.of(
          fieldSerializers[0].createInstance(),
          fieldSerializers[1].createInstance());
    }

    @Override
    public Pair copy(Pair from) {
      return Pair.of(
          fieldSerializers[0].copy(from.getFirst()),
          fieldSerializers[1].copy(from.getSecond()));
    }

    @Override
    public Pair copy(Pair from, Pair reuse) {
      return from;
    }

    @Override
    public void serialize(Pair record, DataOutputView target) throws IOException {
      fieldSerializers[0].serialize(record.getFirst(), target);
      fieldSerializers[1].serialize(record.getSecond(), target);
    }

    @Override
    public Pair deserialize(DataInputView source) throws IOException {
      Object first = fieldSerializers[0].deserialize(source);
      Object second = fieldSerializers[1].deserialize(source);
      return Pair.of(first, second);
    }

    @Override
    public Pair deserialize(Pair reuse, DataInputView source) throws IOException {
      // ~ since pairs are immutable
      return deserialize(source);
    }

    @Override
    public void copy(DataInputView source, DataOutputView target) throws IOException {
      fieldSerializers[0].copy(source, target);
      fieldSerializers[1].copy(source, target);
    }
  }

  private class PairTypeComparatorBuilder implements TypeComparatorBuilder<Pair> {

    private final ArrayList<TypeComparator> fieldComparators = new ArrayList<>();
    private final ArrayList<Integer> logicalKeyFields = new ArrayList<>();

    @Override
    public void initializeTypeComparatorBuilder(int size) {
      fieldComparators.ensureCapacity(size);
      logicalKeyFields.ensureCapacity(size);
    }

    @Override
    public void addComparatorField(int fieldId, TypeComparator<?> comparator) {
      fieldComparators.add(comparator);
      logicalKeyFields.add(fieldId);
    }

    @Override
    public TypeComparator<Pair> createTypeComparator(ExecutionConfig config) {
      Preconditions.checkState(fieldComparators.size() > 0);
      Preconditions.checkState(logicalKeyFields.size() > 0);
      Preconditions.checkState(fieldComparators.size() == logicalKeyFields.size());

      final int maxKey = Collections.max(logicalKeyFields);
      Preconditions.checkState(maxKey >= 0);

      TypeSerializer<?>[] fieldSerializers = new TypeSerializer<?>[maxKey + 1];

      for (int i = 0; i <= maxKey; i++) {
        fieldSerializers[i] = types[i].createSerializer(config);
      }

      return new PairComparator(listToPrimitives(logicalKeyFields),
                                   fieldComparators.toArray(new TypeComparator[fieldComparators.size()]),
                                   fieldSerializers);
    }
  }

  static int[] listToPrimitives(ArrayList<Integer> ints) {
    int[] result = new int[ints.size()];
    for (int i = 0; i < result.length; i++) {
      result[i] = ints.get(i);
    }
    return result;
  }

  public static final class PairComparator extends TupleComparatorBase<Pair> {

    private static final long serialVersionUID = 1L;

    public PairComparator(int[] keyPositions, TypeComparator<?>[] comparators, TypeSerializer<?>[] serializers) {
      super(keyPositions, comparators, serializers);
    }

    private PairComparator(PairComparator toClone) {
      super(toClone);
    }

    // --------------------------------------------------------------------------------------------
    //  Comparator Methods
    // --------------------------------------------------------------------------------------------

    private static Object getField(Pair x, int pos) {
      switch (pos) {
        case 0: return x.getFirst();
        case 1: return x.getSecond();
        default:
          throw new IndexOutOfBoundsException("No such field for pair: " + pos);
      }
    }

    private static Object getFieldNotNull(Pair x, int pos) {
      Object v = getField(x, pos);
      if (v == null) {
        throw new NullFieldException(pos);
      }
      return v;
    }

    @SuppressWarnings("unchecked")
    @Override
    public int hash(Pair value) {
      int i = 0;
      try {
        int code = this.comparators[0].hash(getFieldNotNull(value, keyPositions[0]));
        for (i = 1; i < this.keyPositions.length; i++) {
          code *= HASH_SALT[i & 0x1F]; // salt code with (i % HASH_SALT.length)-th salt component
          code += this.comparators[i].hash(getFieldNotNull(value, keyPositions[i]));
        }
        return code;
      }
      catch (NullFieldException nfex) {
        throw new NullKeyFieldException(nfex);
      }
      catch (IndexOutOfBoundsException iobex) {
        throw new KeyFieldOutOfBoundsException(keyPositions[i]);
      }
    }

    @SuppressWarnings("unchecked")
    @Override
    public void setReference(Pair toCompare) {
      int i = 0;
      try {
        for (; i < this.keyPositions.length; i++) {
          this.comparators[i].setReference(getFieldNotNull(toCompare, this.keyPositions[i]));
        }
      }
      catch (NullFieldException nfex) {
        throw new NullKeyFieldException(nfex);
      }
      catch (IndexOutOfBoundsException iobex) {
        throw new KeyFieldOutOfBoundsException(keyPositions[i]);
      }
    }

    @SuppressWarnings("unchecked")
    @Override
    public boolean equalToReference(Pair candidate) {
      int i = 0;
      try {
        for (; i < this.keyPositions.length; i++) {
          if (!this.comparators[i].equalToReference(getFieldNotNull(candidate, this.keyPositions[i]))) {
            return false;
          }
        }
        return true;
      }
      catch (NullFieldException nfex) {
        throw new NullKeyFieldException(nfex);
      }
      catch (IndexOutOfBoundsException iobex) {
        throw new KeyFieldOutOfBoundsException(keyPositions[i]);
      }
    }

    @SuppressWarnings("unchecked")
    @Override
    public int compare(Pair first, Pair second) {
      int i = 0;
      try {
        for (; i < keyPositions.length; i++) {
          int keyPos = keyPositions[i];
          int cmp = comparators[i].compare(getFieldNotNull(first, keyPos), getFieldNotNull(second, keyPos));

          if (cmp != 0) {
            return cmp;
          }
        }
        return 0;
      }
      catch (NullFieldException nfex) {
        throw new NullKeyFieldException(nfex);
      }
      catch (IndexOutOfBoundsException iobex) {
        throw new KeyFieldOutOfBoundsException(keyPositions[i]);
      }
    }

    @SuppressWarnings("unchecked")
    @Override
    public void putNormalizedKey(Pair value, MemorySegment target, int offset, int numBytes) {
      int i = 0;
      try {
        for (; i < this.numLeadingNormalizableKeys && numBytes > 0; i++) {
          int len = this.normalizedKeyLengths[i];
          len = numBytes >= len ? len : numBytes;
          this.comparators[i].putNormalizedKey(getFieldNotNull(value, this.keyPositions[i]), target, offset, len);
          numBytes -= len;
          offset += len;
        }
      } catch (NullFieldException nfex) {
        throw new NullKeyFieldException(nfex);
      } catch (NullPointerException npex) {
        throw new NullKeyFieldException(this.keyPositions[i]);
      }
    }

    @Override
    public int extractKeys(Object record, Object[] target, int index) {
      int localIndex = index;
      for(int i = 0; i < comparators.length; i++) {
        localIndex += comparators[i].extractKeys(getField((Pair) record, keyPositions[i]), target, localIndex);
      }
      return localIndex - index;
    }

    public TypeComparator<Pair> duplicate() {
      return new PairComparator(this);
    }
  }
}
