package com.esri.hadoop.hive;

import java.util.ArrayList;
import java.util.Collections;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.hive.ql.exec.Description;
import org.apache.hadoop.hive.ql.exec.UDAF;
import org.apache.hadoop.hive.ql.exec.UDAFEvaluator;
import org.apache.hadoop.hive.ql.metadata.HiveException;
import org.apache.hadoop.io.BytesWritable;

import com.esri.core.geometry.Geometry;
import com.esri.core.geometry.GeometryEngine;
import com.esri.core.geometry.SpatialReference;
import com.esri.core.geometry.ogc.OGCGeometry;
import com.esri.hadoop.hive.GeometryUtils.OGCType;

@Description(
		name = "ST_Aggr_ConvexHull",
		value = "_FUNC_(ST_Geometry) - aggregate convex hull of all geometries passed",
		extended = "Example:\n"
			+ "  SELECT _FUNC_(geometry) FROM source; -- return convex hull of all geometries in source"
		)

// Idea: Geometry Collection
public class ST_Aggr_ConvexHull extends UDAF {
	static final Log LOG = LogFactory.getLog(ST_Aggr_ConvexHull.class.getName());

	public static class AggrIntersectionBinaryEvaluator implements UDAFEvaluator {

		protected int MAX_BUFFER_SIZE = 1000;
		protected ArrayList<Geometry> geometries = new ArrayList<Geometry>(MAX_BUFFER_SIZE);
		SpatialReference spatialRef = null;
		int firstWKID = -2;
		
		/*
		 * Initialize evaluator
		 */
		@Override
			public void init(){

			if (geometries.size() > 0){
				geometries.clear();
			}
		}

		/*
		 * Iterate is called once per row in a table
		 */
		public boolean iterate(BytesWritable geomref) throws HiveException{

			if (geomref == null) {
				return false;
			}

			if (firstWKID == -2) {
				firstWKID = GeometryUtils.getWKID(geomref);
				if (firstWKID != GeometryUtils.WKID_UNKNOWN) {
					spatialRef = SpatialReference.create(firstWKID);
				}
			} else if (firstWKID != GeometryUtils.getWKID(geomref)) {
				LogUtils.Log_SRIDMismatch(LOG, geomref, firstWKID);
				return false;
			}

			addGeometryToBuffer(geomref);

			return (geometries.size() != 0);
		}

		/*
		 * Merge the current state of this evaluator with the result of another evaluator's terminatePartial()
		 */
		public boolean merge(BytesWritable other) throws HiveException {
			if (other == null){
				return false;
			}
			addGeometryToBuffer(other);
			return true;
		}

		public BytesWritable terminatePartial() throws HiveException {
			condAggregateBuffer(true);
			if (geometries.size() == 1) {
				OGCGeometry rslt = OGCGeometry.createFromEsriGeometry(geometries.get(0), spatialRef);
				return GeometryUtils.geometryToEsriShapeBytesWritable(rslt);
			} else {
				return null;
			}
		}

		/*
		 * Return a geometry that is the aggregation of all geometries added up until this point
		 */
		public BytesWritable terminate() throws HiveException{
			// for our purposes, terminate is the same as terminatePartial
			return terminatePartial();
		}

		protected void addGeometryToBuffer(BytesWritable geomref) throws HiveException {
			OGCGeometry ogcGeometry = GeometryUtils.geometryFromEsriShape(geomref);
			addGeometryToBuffer(ogcGeometry.getEsriGeometry());
		}

		private void addGeometryToBuffer(Geometry geom) throws HiveException {
			geometries.add(geom);
			condAggregateBuffer(false);
		}

		/*
		 * If the right conditions are met (or force == true), create a convex hull of the geometries
		 * in the current buffer
		 */
		protected void condAggregateBuffer(boolean force) throws HiveException {

			if (force || geometries.size() > MAX_BUFFER_SIZE){
				Geometry[] geomArray = new Geometry[geometries.size()];
				geometries.toArray(geomArray);
				geometries.clear();

				try {
					LOG.trace("performing convexHull");
					Geometry[] convexResult = GeometryEngine.convexHull(geomArray, true);
					Collections.addAll(geometries, convexResult);  // expect one
				} catch (Exception e) {
					LOG.error("exception thrown", e);
				}
			}
		}

	}
}