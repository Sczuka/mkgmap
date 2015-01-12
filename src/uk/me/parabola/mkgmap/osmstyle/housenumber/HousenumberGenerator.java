/*
 * Copyright (C) 2013.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 3 or
 * version 2 as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 */

package uk.me.parabola.mkgmap.osmstyle.housenumber;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.TreeSet;

import uk.me.parabola.imgfmt.Utils;
import uk.me.parabola.imgfmt.app.Area;
import uk.me.parabola.imgfmt.app.Coord;
import uk.me.parabola.imgfmt.app.CoordNode;
import uk.me.parabola.imgfmt.app.net.NumberStyle;
import uk.me.parabola.imgfmt.app.net.Numbers;
import uk.me.parabola.log.Logger;
import uk.me.parabola.mkgmap.general.LineAdder;
import uk.me.parabola.mkgmap.general.MapRoad;
import uk.me.parabola.mkgmap.reader.osm.Element;
import uk.me.parabola.mkgmap.reader.osm.FakeIdGenerator;
import uk.me.parabola.mkgmap.reader.osm.HousenumberHooks;
import uk.me.parabola.mkgmap.reader.osm.Node;
import uk.me.parabola.mkgmap.reader.osm.POIGeneratorHook;
import uk.me.parabola.mkgmap.reader.osm.Relation;
import uk.me.parabola.mkgmap.reader.osm.TagDict;
import uk.me.parabola.mkgmap.reader.osm.Way;
import uk.me.parabola.util.MultiHashMap;

/**
 * Collects all data required for OSM house number handling and adds the
 * house number information to the roads.
 * 
 * @author WanMil
 */
public class HousenumberGenerator {

	private static final Logger log = Logger
			.getLogger(HousenumberGenerator.class);

	/** Gives the maximum distance between house number element and the matching road */
	private static final double MAX_DISTANCE_TO_ROAD = 150d;
	
	private boolean numbersEnabled;
	
	private MultiHashMap<String, MapRoad> roadByNames;
	private List<MapRoad> roads;
	private MultiHashMap<String, Element> houseNumbers;
	private Map<Long,Node> houseNodes;
	
	private static final short streetTagKey = TagDict.getInstance().xlate("mkgmap:street");
	private static final short addrStreetTagKey = TagDict.getInstance().xlate("addr:street");
	private static final short addrInterpolationTagKey = TagDict.getInstance().xlate("addr:interpolation");	
	private static final short housenumberTagKey = TagDict.getInstance().xlate("mkgmap:housenumber");		
	public HousenumberGenerator(Properties props) {
		this.roadByNames = new MultiHashMap<String,MapRoad>();
		this.houseNumbers = new MultiHashMap<String,Element>();
		this.roads = new ArrayList<MapRoad>();
		this.houseNodes = new HashMap<>();
		
		numbersEnabled=props.containsKey("housenumbers");
	}

	/**
	 * Retrieves the street name of this element.
	 * @param e an OSM element
	 * @return the street name (or {@code null} if no street name set)
	 */
	private static String getStreetname(Element e) {
		String streetname = e.getTag(streetTagKey);
		if (streetname == null) {
			streetname = e.getTag(addrStreetTagKey);
		}	
		return streetname;
	}
	
	/**
	 * Adds a node for house number processing.
	 * @param n an OSM node
	 */
	public void addNode(Node n) {
		if (numbersEnabled == false) {
			return;
		}
		if ("true".equals(n.getTag(POIGeneratorHook.AREA2POI_TAG)))
			return; 		
		if (HousenumberMatch.getHousenumber(n) != null) {
			String streetname = getStreetname(n);
			if (streetname != null) {
				houseNumbers.add(streetname, n);
				if (n.getTag(HousenumberHooks.partOfInterpolationTagKey) != null)
					houseNodes.put(n.getId(),n);
			} else {
				if (log.isDebugEnabled())
					log.debug(n.toBrowseURL()," ignored, doesn't contain a street name.");
			}
		}
	}
	
	/**
	 * Adds a way for house number processing.
	 * @param w a way
	 */
	public void addWay(Way w) {
		if (numbersEnabled == false) {
			return;
		}
		
		String ai = w.getTag(addrInterpolationTagKey);
		if (ai != null){
			String nodeIds = w.getTag(HousenumberHooks.mkgmapNodeIdsTagKey);
			if (nodeIds != null){
				List<Node> nodes = new ArrayList<>();
				String[] ids = nodeIds.split(",");
				for (String id : ids){
					Node node = houseNodes.get(Long.decode(id));
					if (node != null){
						nodes.add(node);
					}
				}
				doInterpolation(w,nodes);
			}
		}
		
		
		if (HousenumberMatch.getHousenumber(w) != null) {
			String streetname = getStreetname(w);
			if (streetname != null) {
				houseNumbers.add(streetname, w);
			} else {
				if (log.isDebugEnabled()){
					if (FakeIdGenerator.isFakeId(w.getId()))
						log.debug("mp-created way ignored, doesn't contain a street name. Tags:",w.toTagString());
					else 
						log.debug(w.toBrowseURL()," ignored, doesn't contain a street name.");
				}
			}
		}
	}
	
	/**
	 * Use the information provided by the addr:interpolation tag
	 * to generate additional house number elements. This increases
	 * the likelihood that a road segment is associated with the right 
	 * number ranges. 
	 * @param w the way
	 * @param nodes list of nodes
	 */
	private void doInterpolation(Way w, List<Node> nodes) {
		int numNodes = nodes.size();
		String addrInterpolationMethod = w.getTag(addrInterpolationTagKey);
		int step = 0;
		switch (addrInterpolationMethod) {
		case "all":
		case "1":
			step = 1;
			break;
		case "even":
		case "odd":
		case "2":
			step = 2;
			break;
		default:
			break;
		}
		if (step == 0)
			return; // should not happen here
		int pos = 0;
		for (int i = 0; i+1 < numNodes; i++){
			// the way have other points, find the sequence including the pair of nodes    
			Node n1 = nodes.get(i);
			Node n2 = nodes.get(i+1);
			int pos1 = -1, pos2 = -1;
			for (int k = pos; k < w.getPoints().size(); k++){
				if (w.getPoints().get(k) == n1.getLocation()){
					pos1 = k;
					break;
				}
			}
			if (pos1 < 0){
				log.error("addr:interpolation node not found in way",w);
				return;
			}
			for (int k = pos1+1; k < w.getPoints().size(); k++){
				if (w.getPoints().get(k) == n2.getLocation()){
					pos2 = k;
					break;
				}
			}
			if (pos2 < 0){
				log.error("addr:interpolation node not found in way",w);
				return;
			}
			pos = pos2;
			String street1 = getStreetname(n1);
			String street2 = getStreetname(n2);
			if (street1 != null && street1.equals(street2)){
				try {
					HousenumberMatch m1 = new HousenumberMatch(n1);
					HousenumberMatch m2 = new HousenumberMatch(n2);
					int start = m1.getHousenumber();
					int end = m2.getHousenumber();
					int steps, usedStep;
					if (start < end){
						steps = (end - start) / step - 1;
						usedStep = step;
						
					} else {
						steps = (start - end) / step - 1;
						usedStep = - step;
					}
					if (steps <= 0){
						log.info(w.toBrowseURL(),"addr:interpolation way segment ignored, no number between",start,"and",end);
						continue;
					}
					if ("even".equals(addrInterpolationMethod) && (start % 2 != 0 || end % 2 != 0)){
						log.info(w.toBrowseURL(),"addr:interpolation=even is used with odd housenumber(s)",start,end);
						continue;
					}
					if ("odd".equals(addrInterpolationMethod) && (start % 2 == 0 || end % 2 == 0)){
						log.info(w.toBrowseURL(),"addr:interpolation=odd is used with even housenumber(s)",start,end);
						continue;
					}
					List<Coord> interpolated = getPointsOnWay(w, pos1, pos2, steps);
					if (interpolated == null || interpolated.isEmpty())
						continue;
					int hn = start; 
					StringBuilder sb = new StringBuilder();
					for (Coord co : interpolated){
						hn += usedStep;
						Node generated = new Node(FakeIdGenerator.makeFakeId(), co);
						generated.addTag(streetTagKey, street1);
						String number = String.valueOf(hn);
						generated.addTag(housenumberTagKey, number);
						if (log.isDebugEnabled()){
							sb.append(number);
							sb.append(",");
						}
						houseNumbers.add(street1, generated);
					}
					if (log.isDebugEnabled()){
						if (sb.length() > 0)
							sb.setLength(sb.length()-1);
						log.debug(w.toBrowseURL(), addrInterpolationMethod, "added interpolated number(s)" , sb.toString(),"to",street1);
					}
				} catch (IllegalArgumentException exp) {
					log.debug(exp);
				}
			}
		}
	}

	/**
	 * Calculate the wanted number of coords on a way so that they have
	 * similar distances to each other (and to the first and last point 
	 * of the way).
	 * @param points list of points that build the way
	 * @param num the wanted number 
	 * @return a list with the number of points or the empty list in 
	 * case of errors
	 */
	private List<Coord> getPointsOnWay(Way w, int i1, int i2, int num){
		List<Coord> interpolated = new ArrayList<>(num);
		if (i2 >= w.getPoints().size())
			return interpolated;
		List<Coord> points = w.getPoints().subList(i1, i2+1);
		if (points.size() < 2)
			return interpolated;
		double wayLen = 0;
		for (int i = 0; i+1 < points.size(); i++){
			wayLen += points.get(i).distance(points.get(i+1));
		}
		double ivlLen = wayLen / (num+1);
		if (ivlLen < 0.1){
			log.info("addr:interpolation",w.toBrowseURL(),"segment ignored, would generate",num,"houses with distance of",ivlLen,"m");
			return interpolated;
		}
		int pos = 0;
		double rest = 0;
		while (pos+1 < points.size()){
			Coord c1 = points.get(pos);
			Coord c2 = points.get(pos+1);
			pos++;
			double neededPartOfSegment = 0;
			double segmentLen = c1.distance(c2);
			for(;;){
				neededPartOfSegment += ivlLen - rest;
				if (neededPartOfSegment <= segmentLen){
					double fraction = neededPartOfSegment / segmentLen;
					Coord c = c1.makeBetweenPoint(c2, fraction);
					interpolated.add(c);
					if (interpolated.size() >= num)
						return interpolated;
					rest = 0;
				} else {
					rest = segmentLen - neededPartOfSegment + ivlLen;
					break;
				}
			}
			
		}
		log.warn("addr:interpolation",w.toBrowseURL(),"interpolation for segment",i1,"-",i2,"failed");
		return interpolated;
	}
	
	/**
	 * Adds a road to be processed by the house number generator.
	 * @param osmRoad the OSM way the defines the road 
	 * @param road a road
	 */
	public void addRoad(Way osmRoad, MapRoad road) {
		roads.add(road);
		if (numbersEnabled) {
			String name = getStreetname(osmRoad); 
			if (name != null) {
				if (log.isDebugEnabled())
					log.debug("Housenumber - Streetname:", name, "Way:",osmRoad.getId(),osmRoad.toTagString());
				roadByNames.add(name, road);
			}
		} 
	}
	
	/**
	 * Evaluate type=associatedStreet relations.
	 */
	public void addRelation(Relation r) {
		if (numbersEnabled == false) 
			return;
		String relType = r.getTag("type");
		// the wiki says that we should also evaluate type=street
		if ("associatedStreet".equals(relType) || "street".equals(relType)){
			List<Element> houses= new ArrayList<>();
			List<Element> streets = new ArrayList<>();
			for (Map.Entry<String, Element> member : r.getElements()) {
				if (member.getValue() instanceof Node) {
					Node node = (Node) member.getValue();
					houses.add(node);
				} else if (member.getValue() instanceof Way) {
					Way w = (Way) member.getValue();
					String role = member.getKey();
					switch (role) {
					case "house":
					case "addr:houselink":
					case "address":
						houses.add(w);
						break;
					case "street":
						streets.add(w);
						break;
					case "":
						if (w.getTag("highway") != null){
							streets.add(w);
							continue;
						}
						String buildingTag = w.getTag("building");
						if (buildingTag != null)
							houses.add(w);
						else 
							log.warn("Relation",r.toBrowseURL(),": role of member",w.toBrowseURL(),"unclear");
						break;
					default:
						log.warn("Relation",r.toBrowseURL(),": don't know how to handle member with role",role);
						break;
					}
				}
			}
			if (houses.isEmpty()){
				if ("associatedStreet".equals(relType))
					log.warn("Relation",r.toBrowseURL(),": ignored, found no houses");
				return;
			}
			String streetName = r.getTag("name");
			String streetNameFromRoads = null;
			boolean nameFromStreetsIsUnclear = false;
			if (streets.isEmpty() == false) {
				for (Element street : streets) {
					String roadName = street.getTag("name");
					if (roadName == null)
						continue;
					if (streetNameFromRoads == null)
						streetNameFromRoads = roadName;
					else if (streetNameFromRoads.equals(roadName) == false)
						nameFromStreetsIsUnclear = true;
				}
			}
			if (streetName == null){
				if (nameFromStreetsIsUnclear == false)
					streetName = streetNameFromRoads;
				else {
					log.warn("Relation",r.toBrowseURL(),": ignored, street name is not clear.");
					return;
				}

			} else {
				if (streetNameFromRoads != null){
					if (nameFromStreetsIsUnclear == false && streetName.equals(streetNameFromRoads) == false){
						log.warn("Relation",r.toBrowseURL(),": street name is not clear, using the name from the way, not that of the relation.");
						streetName = streetNameFromRoads;
					} 
					else if (nameFromStreetsIsUnclear == true){
						log.warn("Relation",r.toBrowseURL(),": street name is not clear, using the name from the relation.");
					}
				} 
			}
			int countOK = 0;
			if (streetName != null && streetName.isEmpty() == false){
				for (Element house : houses) {
					if (addStreetTagFromRel(r, house, streetName) )
						countOK++;
				}
			}
			if (countOK > 0)
				log.info("Relation",r.toBrowseURL(),": added tag mkgmap:street=",streetName,"to",countOK,"of",houses.size(),"house members");
			else 
				log.info("Relation",r.toBrowseURL(),": ignored, the house members all have a addr:street or mkgmap:street tag");
		}
	}
	
	/**
	 * Add the tag mkgmap:street=streetName to the element of the 
	 * relation if it does not already have a street name tag.
	 */
	private boolean addStreetTagFromRel(Relation r, Element house, String streetName){
		String addrStreet = getStreetname(house);
		if (addrStreet == null){
			house.addTag(streetTagKey, streetName);
			if (log.isDebugEnabled())
				log.debug("Relation",r.toBrowseURL(),": adding tag mkgmap:street=" + streetName, "to house",house.toBrowseURL());
			return true;
		}
		else if (addrStreet.equals(streetName) == false){
			if (house.getTag(streetTagKey) != null){
				log.warn("Relation",r.toBrowseURL(),": street name from relation doesn't match existing mkgmap:street tag for house",house.toBrowseURL(),"the house seems to be member of another type=associatedStreet relation");
				house.deleteTag(streetTagKey);
			}
			else 
				log.warn("Relation",r.toBrowseURL(),": street name from relation doesn't match existing name for house",house.toBrowseURL());
		}
		return false;
	}
	
	
	public void generate(LineAdder adder) {
		if (numbersEnabled) {
			
			TreeSet<String> sortedStreetNames = new TreeSet<>();
			for (Entry<String, List<Element>> numbers : houseNumbers.entrySet()) {
				sortedStreetNames.add(numbers.getKey());
			}
			
			// process the roads in alphabetical order. This is not needed
			// but helps when comparing results in the log.
			for (String streetName: sortedStreetNames){
				List<MapRoad> possibleRoads = roadByNames.get(streetName);
				if (possibleRoads.isEmpty()) {
					continue;
				}
				List<Element> numbers = houseNumbers.get(streetName);
				match(0, streetName, numbers, possibleRoads);
			} 		}
		
		for (MapRoad r : roads) {
			adder.add(r);
		}
		
		houseNumbers.clear();
		roadByNames.clear();
		roads.clear();
	}
	
	/**
	 * Sorts house numbers by roads, road segments and position of the house number.
	 * @author WanMil
	 */
	private static class HousenumberMatchComparator implements Comparator<HousenumberMatch> {

		public int compare(HousenumberMatch o1, HousenumberMatch o2) {
			if (o1 == o2) {
				return 0;
			}
			
			if (o1.getRoad() != o2.getRoad()) {
				return o1.getRoad().hashCode() - o2.getRoad().hashCode();
			} 
			
			int dSegment = o1.getSegment() - o2.getSegment();
			if (dSegment != 0) {
				return dSegment;
			}
			
			double dFrac = o1.getSegmentFrac() - o2.getSegmentFrac();
			if (dFrac != 0d) {
				return (int)Math.signum(dFrac);
			}
			
			double dDist = o1.getDistance() - o2.getDistance();
			if (dDist != 0d) {
				return (int)Math.signum(dDist);
			}
			
			return 0;
		}
		
	}
	
	/**
	 * Matches the house numbers of one street name to its OSM elements and roads. 
	 * @param streetname name of street
	 * @param elements a list of OSM elements belonging to this street name
	 * @param roads a list of roads with the given street name
	 * 
	 * TODO: Implement plausibility check to detect wrong matches and random house numbers
	 */
	private static void match(int depth, String streetname, List<Element> elements, List<MapRoad> roads) {
		List<HousenumberMatch> numbersList = new ArrayList<HousenumberMatch>(
				elements.size());
		for (Element element : elements) {
			try {
				HousenumberMatch match = new HousenumberMatch(element);
				if (match.getLocation() == null) {
					// there has been a report that indicates match.getLocation() == null
					// could not reproduce so far but catching it here with some additional
					// information. (WanMil)
					log.error("OSM element seems to have no point.");
					log.error("Element: "+element.toBrowseURL()+" " +element);
					log.error("Please report on the mkgmap mailing list.");
					log.error("Continue creating the map. This should be possible without a problem.");
				} else {
					numbersList.add(match);
				}
			} catch (IllegalArgumentException exp) {
				log.debug(exp);
			}
		}
		
		MultiHashMap<MapRoad, HousenumberMatch> roadNumbers = new MultiHashMap<MapRoad, HousenumberMatch>(); 
		
		for (HousenumberMatch n : numbersList) {
			List<HousenumberMatch> sameDistMatches = new ArrayList<>();

			for (MapRoad r : roads) {
				int node = -1;
				Coord c1 = null;
				for (Coord c2 : r.getPoints()) {
					if (c1 != null) {
						Coord cx = n.getLocation();
						double frac = getFrac(c1, c2, cx);
						double dist = distanceToSegment(c1,c2,cx,frac);
						if (dist <= MAX_DISTANCE_TO_ROAD && dist < n.getDistance()) {
							n.setDistance(dist);
							n.setSegmentFrac(frac);
							n.setRoad(r);
							n.setSegment(node);
							sameDistMatches.clear();
						} else if (dist == n.getDistance() && n.getRoad() != r){
							HousenumberMatch sameDist = new HousenumberMatch(n.getElement());
							sameDist.setDistance(dist);
							sameDist.setSegmentFrac(frac);
							sameDist.setRoad(r);
							sameDist.setSegment(node);
							sameDistMatches.add(sameDist);
						}
					}
					c1 = c2;
					node++;
				}
			}
			
			if (n.getRoad() != null) {
				n = checkAngle(n, sameDistMatches);
				Coord c1 = n.getRoad().getPoints().get(n.getSegment());
				Coord c2 = n.getRoad().getPoints().get(n.getSegment()+1);
				n.setLeft(isLeft(c1, c2, n.getLocation()));
				roadNumbers.add(n.getRoad(), n);
			}
		}
		
		// find roads which are very close to each other
		MultiHashMap<Integer, MapRoad> clusters = new MultiHashMap<>();
		List<MapRoad> remaining = new ArrayList<>(roads);
		
		for (int i = 0; i < remaining.size(); i++){
			MapRoad r = remaining.get(i);
			Rectangle bbox = r.getRect();
			clusters.add(i, r);
			while (true){
				boolean changed = false;
				for (int j = remaining.size() - 1; j > i; --j){
					MapRoad r2 = remaining.get(j);
					Rectangle bbox2 = r2.getRect();
					if (bbox.intersects(bbox2)){
						clusters.add(i, r2);
						bbox.add(bbox2);
						remaining.remove(j);
						changed = true;
					}
				}
				if (!changed)
					break;
			} 
		}
		
		// go through all roads and apply the house numbers
		for (int cluster = 0; cluster < clusters.size(); cluster++){
			List<MapRoad> clusteredRoads = clusters.get(cluster);
			for (MapRoad r : roads){
				if (clusteredRoads.contains(r) == false)
					continue;
				List<HousenumberMatch> potentialNumbersThisRoad = roadNumbers.get(r);
				if (potentialNumbersThisRoad.isEmpty()) 
					continue;

				List<HousenumberMatch> leftNumbers = new ArrayList<HousenumberMatch>();
				List<HousenumberMatch> rightNumbers = new ArrayList<HousenumberMatch>();
				for (HousenumberMatch hr : potentialNumbersThisRoad) {
					if (hr.isLeft()) {
						leftNumbers.add(hr);
					} else {
						rightNumbers.add(hr);
					}
				}

				Collections.sort(leftNumbers, new HousenumberMatchComparator());
				Collections.sort(rightNumbers, new HousenumberMatchComparator());

				List<Numbers> numbersListing = new ArrayList<Numbers>();

				log.info("Housenumbers for",r.getName(),r.getCity());
				log.info("Numbers:",potentialNumbersThisRoad);

				int n = 0;
				int nodeIndex = 0;
				int lastRoutableNodeIndex = 0;
				int lastSegment = r.getPoints().size() - 2; 
				for (Coord p : r.getPoints()) {
					if (n == 0) {
						assert p instanceof CoordNode; 
					}

					// An ordinary point in the road.
					if (p.isNumberNode() == false) {
						n++;
						continue;
					}

					// The first time round, this is guaranteed to be a CoordNode
					if (n == 0) {
						nodeIndex++;
						n++;
						continue;
					}

					// Now we have a CoordNode and it is not the first one.
					int leftUsed = 0, rightUsed = 0;
					Numbers numbers = null;

					for (int count = 0; count < 3; count++){ // max. number of tries to correct something 
						numbers = new Numbers();
						numbers.setRnodNumber(lastRoutableNodeIndex);
						leftUsed = applyNumbers(numbers,leftNumbers,n,true);
						rightUsed = applyNumbers(numbers,rightNumbers,n,false);
						if (numbers.isPlausible())
							break; // the normal case
						// try to correct something
						boolean changed = false;
						if (r.getRoadDef().getId() == 10049262){
							long dd = 4;
						}
						if (numbers.getLeftNumberStyle() != numbers.getRightNumberStyle()
								&& (numbers.getLeftNumberStyle() == NumberStyle.BOTH || numbers.getRightNumberStyle() == NumberStyle.BOTH)) {
							if (numbers.getLeftNumberStyle() == NumberStyle.BOTH)
								changed = tryToFindCorrection(leftNumbers, leftUsed, rightNumbers, numbers.getRightNumberStyle(), lastSegment);
							else if (numbers.getRightNumberStyle() == NumberStyle.BOTH)
								changed = tryToFindCorrection(rightNumbers,rightUsed, leftNumbers, numbers.getLeftNumberStyle(), lastSegment);
						}
						if (!changed){
							int chgPos = -1;
							if (n > lastSegment && r.getPoints().get(lastSegment).isNumberNode() == false){
								chgPos = lastSegment; // add number node before end of road 
							} else if (lastRoutableNodeIndex == 0 && r.getPoints().get(1).isNumberNode() == false){
								chgPos = 1; // add number node at start of road
							}
							if (chgPos > 0){
								Coord toChange = r.getPoints().get(chgPos);
								log.debug("adding house number node to", r, "at" + toChange.toDegreeString(),"to fix unplausibile house number interval");
								toChange.setNumberNode(true);
								match(depth+1,streetname, elements, roads);
								return;

							}
							log.error("numbers not (yet) plausible:",r,numbers,"left:",leftNumbers.subList(0, leftUsed),"right:",rightNumbers.subList(0, rightUsed));
							break;
						}
					} 

					leftNumbers.subList(0, leftUsed).clear();
					rightNumbers.subList(0, rightUsed).clear(); 				
					if (log.isInfoEnabled()) {
						log.info("Left: ",numbers.getLeftNumberStyle(),numbers.getRnodNumber(),"Start:",numbers.getLeftStart(),"End:",numbers.getLeftEnd(), "Remaining: "+leftNumbers);
						log.info("Right:",numbers.getRightNumberStyle(),numbers.getRnodNumber(),"Start:",numbers.getRightStart(),"End:",numbers.getRightEnd(), "Remaining: "+rightNumbers);
					}

					numbersListing.add(numbers);

					lastRoutableNodeIndex = nodeIndex;
					nodeIndex++;
					n++;
				}

				r.setNumbers(numbersListing);
			}
			int errors = checkPlausibility(clusteredRoads, roadNumbers);
		}
	}
	

	private static int checkPlausibility(List<MapRoad> clusteredRoads,
			MultiHashMap<MapRoad, HousenumberMatch> roadNumbers) {
		int countErrors = 0;
		for (int i = 0; i < clusteredRoads.size(); i++){
			MapRoad r = clusteredRoads.get(i);
			for (int j = i; j < clusteredRoads.size(); j++){
				MapRoad r2 = clusteredRoads.get(j);
				List<HousenumberMatch> houses = roadNumbers.get(r2);
				if (houses.isEmpty()) 
					continue;
				int errors = checkPlausibility(r, houses, r.getNumbers(), r == r2);
				countErrors += errors;
			}
		}
		return countErrors;
	}

	/**
	 * Check if the existing numbers are found at the right places. 
	 * @param r
	 * @param houses
	 * @param numbersListing
	 * @param badMatches 
	 */
	private static int checkPlausibility(MapRoad r, List<HousenumberMatch> houses, List<Numbers> numbersListing, boolean shouldExist) {
		if (numbersListing == null || numbersListing.isEmpty()){
			if (shouldExist)
				return houses.size();
			return 0;
		}
		boolean nodeAdded = false;
		System.out.println("checking road " + r + " in "  + r.getCity() + " " + numbersListing);
		//		BitSet tested = new BitSet();
		int countErrors = 0;
		for (HousenumberMatch house :houses ){
//			if (house.getRoad() != r)
//				continue;
			int hn = house.getHousenumber();
			int matches = 0;
			if (house.getElement().getId() == 133976941){
				long dd = 4;
			}
			//			if (tested.get(hn))
			//				continue;
			//			tested.set(hn);
			Numbers last = null;
			Numbers firstMatch = null;
			for (Numbers numbers : numbersListing){
				int n = numbers.countMatches(hn);
				if (hn == 10 && r.getRoadDef().getId() == 9800057 && n > 0){
					long dd = 4;
				}
				if (n > 0 && firstMatch == null)
					firstMatch = numbers;
				if (n == 1 && matches > 0){
					if (last.getLeftEnd() == numbers.getLeftStart() && last.getLeftEnd() == hn || 
							last.getRightEnd() == numbers.getRightStart() && last.getRightEnd() == hn ||
							last.getLeftStart() == numbers.getLeftEnd() && last.getLeftStart() == hn||
							last.getRightStart() == numbers.getRightEnd() && last.getRightStart() == hn){
						n = 0; // intervals are overlapping, probably two houses (e.g. 2a,2b) at a T junction
					}
				}
				matches += n;
				if (numbers.getLeftNumberStyle() != NumberStyle.NONE || numbers.getRightNumberStyle() != NumberStyle.NONE)
					last = numbers;
			}
			if (shouldExist){
				if (matches == 0){
					++countErrors;
					if (house.getDistance() < MAX_DISTANCE_TO_ROAD / 2){
						// try to add node so that this is found 

						for (int i = 0; i < 2; i++){
							Coord c = r.getPoints().get(house.getSegment() + i);
							if (c.isNumberNode() == false){
								c.setNumberNode(true);
								nodeAdded = true;
							}
						}

					}
					log.error(r.getName(),hn,house.getElement().toBrowseURL(),"will not be found" );
				}
				else if (matches > 1){
					++countErrors;
					log.error(r.getName(),hn,house.getElement().toBrowseURL(),"is coded in",matches,"different road segments");
				}
				
			} else {
				if (matches > 0){
					++countErrors;
					log.error("housenumber", hn,house.getElement().toBrowseURL(),"is found in other road",r);
				}
			}
		}
		if (shouldExist){
			return (nodeAdded) ? countErrors : -countErrors;
		} else 
			return -countErrors;
	}
	/**
	 * If the closest point to a road is a junction, try to find the road
	 * segment that forms a right angle with the house 
	 * @param currentMatch one match that is closest to the node
	 * @param sameDistMatches  the other matches with the same distance
	 * @return the best match
	 */
	private static HousenumberMatch checkAngle(HousenumberMatch currentMatch,
			List<HousenumberMatch> sameDistMatches) {
		
		if (sameDistMatches.isEmpty())
			return currentMatch;
		// a house has the same distance to different road objects
		// if this happens at a T-junction, make sure not to use the end of the wrong road 
		Coord c1 = currentMatch.getRoad().getPoints().get(currentMatch.getSegment());
		Coord c2 = currentMatch.getRoad().getPoints().get(currentMatch.getSegment()+1);
		Coord cx = currentMatch.getLocation();
		double dist = currentMatch.getDistance();
		HousenumberMatch bestMatch = currentMatch;
		for (HousenumberMatch alternative : sameDistMatches){
			double dist1 = cx.distance(c1);
			double angle, altAngle;
			if (dist1 == dist)
				angle = Utils.getAngle(c2, c1, cx);
			else 
				angle = Utils.getAngle(c1, c2, cx);
			Coord c3 = alternative.getRoad().getPoints().get(alternative.getSegment());
			Coord c4 = alternative.getRoad().getPoints().get(alternative.getSegment()+1);
			
			double dist3 = cx.distance(c3);
			if (dist3 == dist)
				altAngle = Utils.getAngle(c4, c3, cx);
			else 
				altAngle = Utils.getAngle(c3, c4, cx);
			double delta = 90 - Math.abs(angle);
			double deltaAlt = 90 - Math.abs(altAngle);
			if (delta > deltaAlt){
				bestMatch = alternative;
				c1 = c3;
				c2 = c4;
			} 
		}
		if (currentMatch != bestMatch){
			log.debug("preferring road",bestMatch.getRoad(),"for house number element",bestMatch.getElement().getId(),"instead of", currentMatch.getRoad());
		}
		return bestMatch;
	}

	/**
	 * Apply the given house numbers to the numbers object.
	 * @param numbers the numbers object to be configured
	 * @param housenumbers a list of house numbers
	 * @param maxSegment the highest segment number to use
	 * @param left {@code true} the left side of the street; {@code false} the right side of the street
	 * @return the number of elements in housenumbers that were applied
	 */
	private static int applyNumbers(Numbers numbers, List<HousenumberMatch> housenumbers, int maxSegment, boolean left) {
		NumberStyle style = NumberStyle.NONE;
		
		int assignedNumbers = 0;
		if (housenumbers.isEmpty() == false) {
			// get the sublist of house numbers
			int maxN = -1;
			boolean even = false;
			boolean odd = false;
			boolean inOrder = true;
			int lastNum = -1;
			int lastDiff = 0;
			int highestNum = 0;
			int lowestNum = Integer.MAX_VALUE;
			HashSet<Integer> usedNumbers = new HashSet<>();
			for (int i = 0; i< housenumbers.size(); i++) {
				HousenumberMatch hn = housenumbers.get(i);
				if (hn.getSegment() >= maxSegment) {
					break;
				} else {
					int num = hn.getHousenumber();
					usedNumbers.add(num);
					if (num > highestNum)
						highestNum = num;
					if (num < lowestNum)
						lowestNum = num;
					if (lastNum > 0){
						int diff = num - lastNum;
						if (diff != 0 && lastDiff != 0){
							if(lastDiff * diff < 0){
								inOrder = false;
							}
						}
						lastDiff = diff;
					}
					lastNum = num;
					maxN = i;
					if (num % 2 == 0) {
						even = true;
					} else {
						odd = true;
					}
				}
				
			}
			
			if (maxN >= 0) {
				if (even && odd) {
					style = NumberStyle.BOTH;
				} else if (even) {
					style = NumberStyle.EVEN;
				} else {
					style = NumberStyle.ODD;
				}
				
				int start = housenumbers.get(0).getHousenumber();
				int end = housenumbers.get(maxN).getHousenumber();
				if (start != highestNum && start != lowestNum
						|| end != highestNum && end != lowestNum) {
					// interval of found numbers is larger than start..end, check what to use
					inOrder = false;
					 
					int step = (even && odd) ? 1 : 2;
					int n = lowestNum;
					int missingAll = 0;
					while (n < highestNum){
						n += step;
						if (usedNumbers.contains(n) == false)
							++missingAll;
					}
					int missingAsIs = 0;
					if (missingAll > 0){
						int first = (start < end) ? start : end;
						int last = (start < end) ? end: start;
						n = first;
						while (n < last){
							n += step;
							if (usedNumbers.contains(n) == false)
								++missingAsIs;
						}
					}
					if (missingAll == 0 || missingAll == missingAsIs){
						// all numbers between lowest and highest are here, so we can safely change the interval
						if (start > end){
							log.debug("changing interval from",start+".."+end, "to", highestNum+".."+lowestNum);
							start = highestNum;
							end = lowestNum;
						} else {
							log.debug("changing interval from",start+".."+end, "to", lowestNum+".."+highestNum);
							start = lowestNum;
							end = highestNum;
						}
					}
				}
				if (left) { 
					numbers.setLeftStart(start);
					numbers.setLeftEnd(end);
				} else {
					numbers.setRightStart(start);
					numbers.setRightEnd(end);
				}
				
				if (!inOrder){
					if (log.isDebugEnabled())
						log.debug((left? "left" : "right") ,"numbers not in order:", housenumbers.get(0).getRoad(),housenumbers.subList(0, maxN+1));
				}
				assignedNumbers = maxN + 1;
			}
		}
		
		if (left)
			numbers.setLeftNumberStyle(style);
		else
			numbers.setRightNumberStyle(style);
		return assignedNumbers;
	}
	 	
	private static boolean tryToFindCorrection(List<HousenumberMatch> wrongNumbers,
			int wrongUsed, List<HousenumberMatch> otherNumbers,
			NumberStyle otherNumberStyle, int lastSegment) {
		int even = 0, odd = 0;
		for (int i = 0; i < wrongUsed; i++){
			if (wrongNumbers.get(i).getHousenumber() % 2 == 0)
				++even;
			else 
				++odd;
		}
		int searchedRest = -1;
		if (even == 1 && odd > 1 )
			searchedRest = 0;
		else if (odd == 1 && even > 1 )
			searchedRest = 1;
		else 
			return false;
		
		for (int i = 0; i < wrongUsed; i++){
			HousenumberMatch hnm = wrongNumbers.get(i);
			if (hnm.getHousenumber() % 2 != searchedRest)
				continue;
			wrongNumbers.remove(i);
			boolean moved = false;
			if (hnm.getSegment() == 0 || hnm.getSegment() == lastSegment){
				if (hnm.getSegmentFrac() < 0 || hnm.getSegmentFrac() > 1){
					if (otherNumberStyle == NumberStyle.EVEN && searchedRest == 0
							|| otherNumberStyle == NumberStyle.ODD && searchedRest == 1){
						otherNumbers.add(hnm);
						Collections.sort(otherNumbers, new HousenumberMatchComparator());
						moved = true;
					}
				}
			} 
			if (!moved){
				log.error(hnm.getRoad(),"house number element",hnm,hnm.getElement().toBrowseURL(), "looks wrong, is ignored");
			}
			return true;
		}
		return false;
	}
 	
	/**
	 * Evaluates if the given point lies on the left side of the line spanned by spoint1 and spoint2.
	 * @param spoint1 first point of line
	 * @param spoint2 second point of line
	 * @param point the point to check
	 * @return {@code true} point lies on the left side; {@code false} point lies on the right side
	 */
	private static boolean isLeft(Coord spoint1, Coord spoint2, Coord point) {
		return ((spoint2.getHighPrecLon() - spoint1.getHighPrecLon())
				* (point.getHighPrecLat() - spoint1.getHighPrecLat()) - (spoint2
				.getHighPrecLat() - spoint1.getHighPrecLat())
				* (point.getHighPrecLon() - spoint1.getHighPrecLon())) > 0;
	}
	
	/**
	 * Calculates the distance to the given segment in meter.
	 * @param spoint1 segment point 1
	 * @param spoint2 segment point 2
	 * @param point point
	 * @return the distance in meter
	 */
	private static double distanceToSegment(Coord spoint1, Coord spoint2, Coord point, double frac) {

		if (frac <= 0) {
			return spoint1.distance(point);
		} else if (frac >= 1) {
			return spoint2.distance(point);
		} else {
			return point.distToLineSegment(spoint1, spoint2);
		}

	}
	
	/**
	 * Calculates the fraction at which the given point is closest to the line segment.
	 * @param spoint1 segment point 1
	 * @param spoint2 segment point 2
	 * @param point point
	 * @return the fraction
	 */
	private static double getFrac(Coord spoint1, Coord spoint2, Coord point) {
		int aLon = spoint1.getHighPrecLon();
		int bLon = spoint2.getHighPrecLon();
		int pLon = point.getHighPrecLon();
		int aLat = spoint1.getHighPrecLat();
		int bLat = spoint2.getHighPrecLat();
		int pLat = point.getHighPrecLat();
		
		double deltaLon = bLon - aLon;
		double deltaLat = bLat - aLat;

		if (deltaLon == 0 && deltaLat == 0) 
			return 0;
		else {
			// scale for longitude deltas by cosine of average latitude  
			double scale = Math.cos(Coord.int30ToRadians((aLat + bLat + pLat) / 3) );
			double deltaLonAP = scale * (pLon - aLon);
			deltaLon = scale * deltaLon;
			if (deltaLon == 0 && deltaLat == 0)
				return 0;
			else 
				return (deltaLonAP * deltaLon + (pLat - aLat) * deltaLat) / (deltaLon * deltaLon + deltaLat * deltaLat);
		}
	}
}
