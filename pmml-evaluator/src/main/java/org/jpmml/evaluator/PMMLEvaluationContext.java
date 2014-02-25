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

import org.jpmml.manager.*;

import org.dmg.pmml.*;

public class PMMLEvaluationContext extends EvaluationContext {

	private PMMLManager pmmlManager = null;


	public PMMLEvaluationContext(PMMLManager pmmlManager){
		setPmmlManager(pmmlManager);
	}

	@Override
	public DerivedField resolveDerivedField(FieldName name){
		PMMLManager pmmlManager = getPmmlManager();

		return pmmlManager.getDerivedField(name);
	}

	@Override
	public DefineFunction resolveFunction(String name){
		PMMLManager pmmlManager = getPmmlManager();

		return pmmlManager.getFunction(name);
	}

	@Override
	public FieldValue createFieldValue(FieldName name, Object value){
		PMMLManager pmmlManager = getPmmlManager();

		DataField dataField = pmmlManager.getDataField(name);
		if(dataField != null){
			return FieldValueUtil.create(dataField, value);
		}

		return super.createFieldValue(name, value);
	}

	public PMMLManager getPmmlManager(){
		return this.pmmlManager;
	}

	private void setPmmlManager(PMMLManager pmmlManager){
		this.pmmlManager = pmmlManager;
	}
}