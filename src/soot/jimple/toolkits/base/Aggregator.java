/* Soot - a J*va Optimization Framework
 * Copyright (C) 1997-1999 Raja Vallee-Rai
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Library General Public
 * License as published by the Free Software Foundation; either
 * version 2 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Library General Public License for more details.
 *
 * You should have received a copy of the GNU Library General Public
 * License along with this library; if not, write to the
 * Free Software Foundation, Inc., 59 Temple Place - Suite 330,
 * Boston, MA 02111-1307, USA.
 */

/*
 * Modified by the Sable Research Group and others 1997-1999.  
 * See the 'credits' file distributed with Soot for the complete list of
 * contributors.  (Soot is distributed at http://www.sable.mcgill.ca/soot)
 */





package soot.jimple.toolkits.base;

import soot.*;
import soot.jimple.*;
import soot.toolkits.scalar.*;
import soot.toolkits.graph.*;
import soot.util.*;
import java.util.*;

public class Aggregator extends BodyTransformer
{
    private static Aggregator instance = new Aggregator();
    private Aggregator() {}

    public static Aggregator v() { return instance; }
    
    public static int nodeCount = 0;
    public static int aggrCount = 0;

    public String getDeclaredOptions() { return super.getDeclaredOptions() + " only-stack-locals"; }

    /** Traverse the statements in the given body, looking for
      *  aggregation possibilities; that is, given a def d and a use u,
      *  d has no other uses, u has no other defs, collapse d and u. 
      * 
      * option: only-stack-locals; if this is true, only aggregate variables
                        starting with $ */
    protected void internalTransform(Body b, String phaseName, Map options)
    {
        StmtBody body = (StmtBody)b;
        boolean onlyStackVars = Options.getBoolean(options, "only-stack-locals"); 

        int aggregateCount = 1;

        if(Main.isProfilingOptimization)
            Main.aggregationTimer.start();
         boolean changed = false;

        Map boxToZone = new HashMap(body.getUnits().size() * 2 + 1, 0.7f);

        // Determine the zone of every box
        {
            Zonation zonation = new Zonation(body);
            
            Iterator unitIt = body.getUnits().iterator();
            
            while(unitIt.hasNext())
            {
                Unit u = (Unit) unitIt.next();
                Zone zone = (Zone) zonation.getZoneOf(u);
                
                
                Iterator boxIt = u.getUseAndDefBoxes().iterator();
                           
                while(boxIt.hasNext())
                {
                    ValueBox box = (ValueBox) boxIt.next();                    
                    boxToZone.put(box, zone);
                }   
            }
        }        
        
                     
        do {
            if(Main.isVerbose)
                System.out.println("[" + body.getMethod().getName() + "] Aggregating iteration " + aggregateCount + "...");
        
            // body.printTo(new java.io.PrintWriter(System.out, true));
            
            changed = internalAggregate(body, boxToZone, onlyStackVars);
            
            aggregateCount++;
        } while(changed);
        
        if(Main.isProfilingOptimization)
            Main.aggregationTimer.end();
            
    }
  
  private static boolean internalAggregate(StmtBody body, Map boxToZone, boolean onlyStackVars)
    {
      Iterator stmtIt;
      LocalUses localUses;
      LocalDefs localDefs;
      CompleteUnitGraph graph;
      boolean hadAggregation = false;
      Chain units = body.getUnits();
      
      graph = new CompleteUnitGraph(body);
      localDefs = new SimpleLocalDefs(graph);
      localUses = new SimpleLocalUses(graph, localDefs);
          
      stmtIt = PseudoTopologicalOrderer.v().newList(graph).iterator();
      
      while (stmtIt.hasNext())
        {
          Stmt s = (Stmt)(stmtIt.next());
              
          if (!(s instanceof AssignStmt))
            continue;
          
          Value lhs = ((AssignStmt)s).getLeftOp();
          if (!(lhs instanceof Local))
            continue;
    
          if(onlyStackVars && !((Local) lhs).getName().startsWith("$"))
            continue;
            
          List lu = localUses.getUsesOf((AssignStmt)s);
          if (lu.size() != 1)
            continue;
            
          UnitValueBoxPair usepair = (UnitValueBoxPair)lu.get(0);
          Unit use = usepair.unit;
          ValueBox useBox = usepair.valueBox;
              
          List ld = localDefs.getDefsOfAt((Local)lhs, use);
          if (ld.size() != 1)
            continue;
   
          // Check to make sure aggregation pair in the same zone
            if(boxToZone.get(((AssignStmt) s).getRightOpBox()) != boxToZone.get(usepair.valueBox))
            {
                continue;
            }  
             
          /* we need to check the path between def and use */
          /* to see if there are any intervening re-defs of RHS */
          /* in fact, we should check that this path is unique. */
          /* if the RHS uses only locals, then we know what
             to do; if RHS has a method invocation f(a, b,
             c) or field access, we must ban field writes, other method
             calls and (as usual) writes to a, b, c. */
          
          boolean cantAggr = false;
          boolean propagatingInvokeExpr = false;
          boolean propagatingFieldRef = false;
          boolean propagatingArrayRef = false;
          ArrayList fieldRefList = new ArrayList();
      
          Value rhs = ((AssignStmt)s).getRightOp();
          LinkedList localsUsed = new LinkedList();
          for (Iterator useIt = (s.getUseBoxes()).iterator();
               useIt.hasNext(); )
            {
              Value v = ((ValueBox)(useIt.next())).getValue();
                if (v instanceof Local)
                    localsUsed.add(v);
                else if (v instanceof InvokeExpr)
                    propagatingInvokeExpr = true;
                else if(v instanceof ArrayRef)
                    propagatingArrayRef = true;
                else if(v instanceof FieldRef)
                {
                    propagatingFieldRef = true;
                    fieldRefList.add(v);
                }
            }
          
          // look for a path from s to use in graph.
          // only look in an extended basic block, though.

          List path = graph.getExtendedBasicBlockPathBetween(s, use);
      
          if (path == null)
            continue;

          Iterator pathIt = path.iterator();

          // skip s.
          if (pathIt.hasNext())
            pathIt.next();

          while (pathIt.hasNext() && !cantAggr)
          {
              Stmt between = (Stmt)(pathIt.next());
          
              if(between != use)    
              {
                // Check for killing definitions
                
                for (Iterator it = between.getDefBoxes().iterator();
                       it.hasNext(); )
                  {
                      Value v = ((ValueBox)(it.next())).getValue();
                      if (localsUsed.contains(v))
                      { 
                            cantAggr = true; 
                            break; 
                      }
                      
                      if (propagatingInvokeExpr || propagatingFieldRef || propagatingArrayRef)
                      {
                          if (v instanceof FieldRef)
                          {
                              if(propagatingInvokeExpr)
                              {
                                  cantAggr = true; 
                                  break;
                              }
                              else if(propagatingFieldRef)
                              {
                                  // Can't aggregate a field access if passing a definition of a field 
                                  // with the same name, because they might be aliased

                                  Iterator frIt = fieldRefList.iterator();
                                  while (frIt.hasNext())
                                  {
                                      FieldRef fieldRef = (FieldRef) frIt.next();
                                      if(((FieldRef) v).getField() == fieldRef.getField())
                                      {
                                          cantAggr = true;
                                          break;
                                      } 
                                  }
                              } 
                           }
                           else if(v instanceof ArrayRef)
                           {
                                if(propagatingInvokeExpr)
                                {   
                                    // Cannot aggregate an invoke expr past an array write
                                    cantAggr = true;
                                    break;
                                }
                                else if(propagatingArrayRef)
                                {
                                    // cannot aggregate an array read past a write
                                    // this is somewhat conservative
                                    // (if types differ they may not be aliased)
                                    
                                    cantAggr = true;
                                    break;
                                }
                           }
                      }
                  }
                  
                  // Make sure not propagating past a {enter,exit}Monitor
                    if(propagatingInvokeExpr && between instanceof MonitorStmt)
                        cantAggr = true;
              }  
                            
              // Check for intervening side effects due to method calls
                if(propagatingInvokeExpr || propagatingFieldRef || propagatingArrayRef)
                    {
                      for (Iterator useIt = (between.getUseBoxes()).iterator();
                           useIt.hasNext(); )
                        {
                          ValueBox box = (ValueBox) useIt.next();
                          
                          if(between == use && box == useBox)
                          {
                                // Reached use point, stop looking for
                                // side effects
                                break;
                          }
                          
                          Value v = box.getValue();
                          
                            if (v instanceof InvokeExpr || 
                                (propagatingInvokeExpr && (v instanceof FieldRef || v instanceof ArrayRef)))
                            {
                                cantAggr = true;
                                break;
                            }
                            
                        }
                    }
            }

          // we give up: can't aggregate.
          if (cantAggr)
          {
            continue;
          }
          /* assuming that the d-u chains are correct, */
          /* we need not check the actual contents of ld */
          
          Value aggregatee = ((AssignStmt)s).getRightOp();
          
          if (usepair.valueBox.canContainValue(aggregatee))
            {
              usepair.valueBox.setValue(aggregatee);
              units.remove(s);
              hadAggregation = true;
              aggrCount++;
            }
          else
            {/*
            if(Main.isVerbose)
            {
                System.out.println("[debug] failed aggregation");
                  System.out.println("[debug] tried to put "+aggregatee+
                                 " into "+usepair.stmt + 
                                 ": in particular, "+usepair.valueBox);
                  System.out.println("[debug] aggregatee instanceof Expr: "
                                 +(aggregatee instanceof Expr));
            }*/
            }
        }
      return hadAggregation;
    }
        
}

