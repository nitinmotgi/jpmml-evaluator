/*
 * Copyright (c) 2013 Villu Ruusmann
 *
 * This file is part of JPMML-Evaluator
 *
 * JPMML-Evaluator is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * JPMML-Evaluator is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with JPMML-Evaluator.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.jpmml.evaluator;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.google.common.base.Function;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableRangeSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Range;
import com.google.common.collect.RangeSet;
import com.google.common.collect.TreeRangeSet;
import org.dmg.pmml.DataField;
import org.dmg.pmml.DataType;
import org.dmg.pmml.Field;
import org.dmg.pmml.Interval;
import org.dmg.pmml.InvalidValueTreatmentMethod;
import org.dmg.pmml.MiningField;
import org.dmg.pmml.MissingValueTreatmentMethod;
import org.dmg.pmml.OpType;
import org.dmg.pmml.OutlierTreatmentMethod;
import org.dmg.pmml.PMMLObject;
import org.dmg.pmml.Target;
import org.dmg.pmml.TypeDefinitionField;
import org.dmg.pmml.Value;

public class FieldValueUtil {

	private FieldValueUtil(){
	}

	static
	public FieldValue prepareInputValue(DataField dataField, MiningField miningField, Object value){
		DataType dataType = dataField.getDataType();
		OpType opType = dataField.getOpType();

		if(dataType == null || opType == null){
			throw new InvalidFeatureException(dataField);
		}

		boolean compatible;

		try {
			if(value != null){
				value = TypeUtil.parseOrCast(dataType, value);
			}

			compatible = true;
		} catch(IllegalArgumentException | TypeCheckException e){
			compatible = false;
		}

		Value.Property status = getStatus(dataField, miningField, value, compatible);
		switch(status){
			case VALID:
				return performValidValueTreatment(dataField, miningField, value);
			case INVALID:
				return performInvalidValueTreatment(dataField, miningField, value);
			case MISSING:
				return performMissingValueTreatment(dataField, miningField);
			default:
				break;
		}

		throw new EvaluationException();
	}

	static
	public FieldValue prepareTargetValue(DataField dataField, MiningField miningField, Target target, Object value){
		DataType dataType = dataField.getDataType();
		OpType opType = dataField.getOpType();

		if(dataType == null || opType == null){
			throw new InvalidFeatureException(dataField);
		}

		String missingValueReplacement = miningField.getMissingValueReplacement();
		if(missingValueReplacement != null){
			throw new InvalidFeatureException(miningField);
		}

		return createTargetValue(dataField, miningField, target, value);
	}

	static
	public FieldValue performValidValueTreatment(Field field, MiningField miningField, Object value){
		OutlierTreatmentMethod outlierTreatmentMethod = miningField.getOutlierTreatment();

		Double lowValue = miningField.getLowValue();
		Double highValue = miningField.getHighValue();

		Double doubleValue = null;

		switch(outlierTreatmentMethod){
			case AS_MISSING_VALUES:
			case AS_EXTREME_VALUES:
				{
					if(lowValue == null || highValue == null){
						throw new InvalidFeatureException(miningField);
					} // End if

					if((lowValue).compareTo(highValue) > 0){
						throw new InvalidFeatureException(miningField);
					}

					doubleValue = (Double)TypeUtil.parseOrCast(DataType.DOUBLE, value);
				}
				break;
			default:
				break;
		} // End switch

		switch(outlierTreatmentMethod){
			case AS_IS:
				break;
			case AS_MISSING_VALUES:
				{
					if((doubleValue).compareTo(lowValue) < 0 || (doubleValue).compareTo(highValue) > 0){
						return createMissingInputValue(field, miningField);
					}
				}
				break;
			case AS_EXTREME_VALUES:
				{
					if((doubleValue).compareTo(lowValue) < 0){
						value = lowValue;
					} else

					if((doubleValue).compareTo(highValue) > 0){
						value = highValue;
					}
				}
				break;
			default:
				throw new UnsupportedFeatureException(miningField, outlierTreatmentMethod);
		}

		return createInputValue(field, miningField, value);
	}

	static
	public FieldValue performInvalidValueTreatment(Field field, MiningField miningField, Object value){
		InvalidValueTreatmentMethod invalidValueTreatmentMethod = miningField.getInvalidValueTreatment();

		switch(invalidValueTreatmentMethod){
			case AS_IS:
				return createInputValue(field, miningField, value);
			case AS_MISSING:
				return createMissingInputValue(field, miningField);
			case RETURN_INVALID:
				throw new InvalidResultException(miningField);
			default:
				throw new UnsupportedFeatureException(miningField, invalidValueTreatmentMethod);
		}
	}

	static
	public FieldValue performMissingValueTreatment(Field field, MiningField miningField){
		MissingValueTreatmentMethod missingValueTreatmentMethod = miningField.getMissingValueTreatment();

		if(missingValueTreatmentMethod == null){
			missingValueTreatmentMethod = MissingValueTreatmentMethod.AS_IS;
		}

		switch(missingValueTreatmentMethod){
			case AS_IS:
			case AS_MEAN:
			case AS_MEDIAN:
			case AS_MODE:
			case AS_VALUE:
				return createMissingInputValue(field, miningField);
			default:
				throw new UnsupportedFeatureException(miningField, missingValueTreatmentMethod);
		}
	}

	@SuppressWarnings (
		value = {"fallthrough"}
	)
	static
	private Value.Property getStatus(DataField dataField, MiningField miningField, Object value, boolean compatible){

		if(value == null){
			return Value.Property.MISSING;
		}

		boolean hasValidSpace = false;

		if(dataField.hasValues()){
			DataType dataType = dataField.getDataType();
			OpType opType = dataField.getOpType();

			if(dataField instanceof HasParsedValueMapping){
				HasParsedValueMapping<?> hasParsedValueMapping = (HasParsedValueMapping<?>)dataField;

				Value fieldValue = getValidValue(hasParsedValueMapping, dataType, opType, value);
				if(fieldValue != null){
					return Value.Property.VALID;
				}
			}

			List<Value> fieldValues = dataField.getValues();
			for(int i = 0, max = fieldValues.size(); i < max; i++){
				Value fieldValue = fieldValues.get(i);

				Value.Property property = fieldValue.getProperty();
				switch(property){
					case VALID:
						{
							hasValidSpace = true;

							if(!compatible){
								continue;
							}
						}
						// Falls through
					case INVALID:
					case MISSING:
						{
							boolean equals = equals(dataType, value, fieldValue.getValue());

							if(equals){
								return property;
							}
						}
						break;
					default:
						throw new UnsupportedFeatureException(fieldValue, property);
				}
			}
		} // End if

		if(!compatible){
			return Value.Property.INVALID;
		} // End if

		if(dataField.hasIntervals()){
			PMMLObject locatable = miningField;

			OpType opType = miningField.getOpType();
			if(opType == null){
				locatable = dataField;

				opType = dataField.getOpType();
			}

			switch(opType){
				case CONTINUOUS:
					{
						RangeSet<Double> validRanges = getValidRanges(dataField);

						Double doubleValue = (Double)TypeUtil.parseOrCast(DataType.DOUBLE, value);

						// "If intervals are present, then a value that is outside the intervals is considered invalid"
						return (validRanges.contains(doubleValue) ? Value.Property.VALID : Value.Property.INVALID);
					}
				case CATEGORICAL:
				case ORDINAL:
					// "Intervals are not allowed for non-continuous fields"
					throw new InvalidFeatureException(dataField);
				default:
					throw new UnsupportedFeatureException(locatable, opType);
			}
		}

		// "If a field contains at least one Value element where the value of property is valid, then the set of Value elements completely defines the set of valid values"
		if(hasValidSpace){
			return Value.Property.INVALID;
		}

		// "Any value is valid by default"
		return Value.Property.VALID;
	}

	static
	private FieldValue createInputValue(Field field, MiningField miningField, Object value){

		if(value == null){
			return null;
		}

		DataType dataType = field.getDataType();
		OpType opType = field.getOpType();

		// "A MiningField overrides a DataField"
		if(miningField != null){
			opType = firstNonNull(miningField.getOpType(), opType);
		}

		return create(dataType, opType, value);
	}

	static
	private FieldValue createMissingInputValue(Field field, MiningField miningField){
		return createInputValue(field, miningField, miningField.getMissingValueReplacement());
	}

	static
	private FieldValue createTargetValue(Field field, MiningField miningField, Target target, Object value){

		if(value == null){
			return null;
		}

		DataType dataType = field.getDataType();
		OpType opType = field.getOpType();

		// "A MiningField overrides a DataField, and a Target overrides a MiningField"
		if(miningField != null){
			opType = firstNonNull(miningField.getOpType(), opType);

			if(target != null){
				opType = firstNonNull(target.getOpType(), opType);
			}
		}

		return create(dataType, opType, value);
	}

	static
	public FieldValue create(Object value){
		return create((DataType)null, (OpType)null, value);
	}

	static
	public List<FieldValue> createAll(List<?> values){
		Function<Object, FieldValue> function = new Function<Object, FieldValue>(){

			@Override
			public FieldValue apply(Object value){
				return create(value);
			}
		};

		return Lists.transform(values, function);
	}

	static
	public FieldValue create(Field field, Object value){
		FieldValue result = create(field.getDataType(), field.getOpType(), value);

		if(field instanceof TypeDefinitionField){
			return enhance((TypeDefinitionField)field, result);
		}

		return result;
	}

	static
	public FieldValue create(DataType dataType, OpType opType, Object value){

		if(value == null){
			return null;
		} // End if

		if(value instanceof Collection){
			Collection<?> collection = (Collection<?>)value;

			if(dataType == null){
				Object firstElement = Iterables.getFirst(collection, null);

				if(firstElement != null){
					dataType = TypeUtil.getDataType(firstElement);
				} else

				{
					dataType = DataType.STRING;
				}
			} // End if

			if(opType == null){
				opType = TypeUtil.getOpType(dataType);
			}
		} else

		{
			if(dataType == null){
				dataType = TypeUtil.getDataType(value);
			} else

			{
				value = TypeUtil.parseOrCast(dataType, value);
			} // End if

			if(opType == null){
				opType = TypeUtil.getOpType(dataType);
			}
		}

		switch(opType){
			case CONTINUOUS:
				return ContinuousValue.create(dataType, value);
			case CATEGORICAL:
				return CategoricalValue.create(dataType, value);
			case ORDINAL:
				return OrdinalValue.create(dataType, value);
			default:
				break;
		}

		throw new EvaluationException();
	}

	static
	public FieldValue refine(Field field, FieldValue value){
		FieldValue result = refine(field.getDataType(), field.getOpType(), value);

		if((field instanceof TypeDefinitionField) && (result != value)){
			return enhance((TypeDefinitionField)field, result);
		}

		return result;
	}

	static
	public FieldValue refine(DataType dataType, OpType opType, FieldValue value){

		if(value == null){
			return null;
		}

		DataType valueDataType = value.getDataType();
		OpType valueOpType = value.getOpType();

		DataType refinedDataType = firstNonNull(dataType, valueDataType);
		OpType refinedOpType = firstNonNull(opType, valueOpType);

		if((refinedDataType).equals(valueDataType) && (refinedOpType).equals(valueOpType)){
			return value;
		}

		return create(refinedDataType, refinedOpType, value.getValue());
	}

	static
	private FieldValue enhance(TypeDefinitionField field, FieldValue value){

		if(value == null){
			return null;
		} // End if

		if(value instanceof OrdinalValue){
			OrdinalValue ordinalValue = (OrdinalValue)value;

			ordinalValue.setOrdering(getOrdering(field, ordinalValue.getDataType()));
		}

		return value;
	}

	static
	public Object getValue(FieldValue value){
		return (value != null ? value.getValue() : null);
	}

	static
	public <V> V getValue(Class<? extends V> clazz, FieldValue value){
		return TypeUtil.cast(clazz, getValue(value));
	}

	static
	public Value getValidValue(TypeDefinitionField field, Object value){

		if(value == null){
			return null;
		} // End if

		if(field.hasValues()){
			DataType dataType = field.getDataType();
			OpType opType = field.getOpType();

			value = TypeUtil.parseOrCast(dataType, value);

			if(field instanceof HasParsedValueMapping){
				HasParsedValueMapping<?> hasParsedValueMapping = (HasParsedValueMapping<?>)field;

				return getValidValue(hasParsedValueMapping, dataType, opType, value);
			}

			List<Value> fieldValues = field.getValues();
			for(int i = 0, max = fieldValues.size(); i < max; i++){
				Value fieldValue = fieldValues.get(i);

				Value.Property property = fieldValue.getProperty();
				switch(property){
					case VALID:
						{
							boolean equals = equals(dataType, value, fieldValue.getValue());

							if(equals){
								return fieldValue;
							}
						}
						break;
					case INVALID:
					case MISSING:
						break;
					default:
						throw new UnsupportedFeatureException(fieldValue, property);
				}
			}
		}

		return null;
	}

	static
	private Value getValidValue(HasParsedValueMapping<?> hasParsedValueMapping, DataType dataType, OpType opType, Object object){
		FieldValue value;

		try {
			value = FieldValueUtil.create(dataType, opType, object);
		} catch(IllegalArgumentException | TypeCheckException e){
			return null;
		}

		Map<FieldValue, ?> fieldValues = hasParsedValueMapping.getValueMapping(dataType, opType);

		return (Value)fieldValues.get(value);
	}

	static
	public List<Value> getValidValues(TypeDefinitionField field){

		if(field.hasValues()){
			List<Value> fieldValues = field.getValues();

			List<Value> result = new ArrayList<>(fieldValues.size());

			for(int i = 0, max = fieldValues.size(); i < max; i++){
				Value fieldValue = fieldValues.get(i);

				Value.Property property = fieldValue.getProperty();
				switch(property){
					case VALID:
						result.add(fieldValue);
						break;
					case INVALID:
					case MISSING:
						break;
					default:
						throw new UnsupportedFeatureException(fieldValue, property);
				}
			}

			return result;
		}

		return Collections.emptyList();
	}

	static
	public List<String> getTargetCategories(TypeDefinitionField field){
		return CacheUtil.getValue(field, FieldValueUtil.targetCategoryCache);
	}

	static
	public RangeSet<Double> getValidRanges(DataField dataField){
		return CacheUtil.getValue(dataField, FieldValueUtil.validRangeCache);
	}

	static
	private boolean equals(DataType dataType, Object value, String referenceValue){

		try {
			return TypeUtil.equals(dataType, value, TypeUtil.parse(dataType, referenceValue));
		} catch(IllegalArgumentException | TypeCheckException e){

			// The String representation of invalid or missing values (eg. "N/A") may not be parseable to the requested representation
			try {
				return TypeUtil.equals(DataType.STRING, value, referenceValue);
			} catch(TypeCheckException tce){
				// Ignored
			}

			throw e;
		}
	}

	static
	private <V> V firstNonNull(V value, V defaultValue){

		if(value != null){
			return value;
		}

		return defaultValue;
	}

	static
	private RangeSet<Double> parseValidRanges(DataField dataField){
		RangeSet<Double> result = TreeRangeSet.create();

		List<Interval> intervals = dataField.getIntervals();
		for(Interval interval : intervals){
			Range<Double> range = DiscretizationUtil.toRange(interval);

			result.add(range);
		}

		return result;
	}

	static
	private List<?> getOrdering(TypeDefinitionField field, final DataType dataType){
		List<Value> values = getValidValues(field);
		if(values.isEmpty()){
			return null;
		}

		Function<Value, Object> function = new Function<Value, Object>(){

			@Override
			public Object apply(Value value){
				return TypeUtil.parse(dataType, value.getValue());
			}
		};

		return Lists.newArrayList(Iterables.transform(values, function));
	}

	private static final LoadingCache<TypeDefinitionField, List<String>> targetCategoryCache = CacheUtil.buildLoadingCache(new CacheLoader<TypeDefinitionField, List<String>>(){

		@Override
		public List<String> load(TypeDefinitionField field){
			List<Value> values = getValidValues(field);

			Function<Value, String> function = new Function<Value, String>(){

				@Override
				public String apply(Value value){
					String result = value.getValue();
					if(result == null){
						throw new InvalidFeatureException(value);
					}

					return result;
				}
			};

			return ImmutableList.copyOf(Iterables.transform(values, function));
		}
	});

	private static final LoadingCache<DataField, RangeSet<Double>> validRangeCache = CacheUtil.buildLoadingCache(new CacheLoader<DataField, RangeSet<Double>>(){

		@Override
		public RangeSet<Double> load(DataField dataField){
			return ImmutableRangeSet.copyOf(parseValidRanges(dataField));
		}
	});
}