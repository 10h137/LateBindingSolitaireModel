package savilerow;
/*

    Savile Row http://savilerow.cs.st-andrews.ac.uk/
    Copyright (C) 2014-2020 Peter Nightingale
    
    This file is part of Savile Row.
    
    Savile Row is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
    
    Savile Row is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.
    
    You should have received a copy of the GNU General Public License
    along with Savile Row.  If not, see <http://www.gnu.org/licenses/>.

*/






import java.util.*;

//  Turn MakeTable type into tableshort constraint.

public class TransformMakeTable extends TreeTransformerBottomUpNoWrapper
{
    public static boolean verbose=false;
    
    private static boolean twowatchedlits=false;
    
    private static boolean diskcache=false;   //  Whether to use the persistent cache.
    
    PersistentCache pcache;      // On-disk cache of successful conversions.
    HashSet<String> failCache;   // In-memory cache of failed conversions.
    HashMap<String, ASTNode> memCache;   // In-memory cache of successful conversions, either read from disk or done in this process. 
    
    boolean prop;
    public TransformMakeTable(Model _m, boolean propagate) {
        super(_m);
        if(diskcache) {
            pcache = new PersistentCache();
        }
        failCache=new HashSet<String>();
        memCache=new HashMap<String,ASTNode>();
        prop=propagate;
    }
    public TransformMakeTable(Model _m) {
        super(_m);
        if(diskcache) {
            pcache = new PersistentCache();
        }
        failCache=new HashSet<String>();
        memCache=new HashMap<String,ASTNode>();
        prop=false;
    }
    
    public void saveToCache(String node, ASTNode curnode, ASTNode newTable) {
        // node must be the string containing the expression, domains etc. We pass it in because we already
        // know it, and it is expensive to recalculate
        
        StringBuilder eprime = new StringBuilder();
        eprime.append("letting tab" + PersistentCache.getHash(node) + " = ");
        
        ASTNode table=newTable.getChildConst(1);
        if(!(table.isMatrixLiteral())) {
            System.out.println(table.toString());
            CmdFlags.errorExit("Confused ShortTable");
        }
        
        eprime.append(table.toString());
        if(diskcache) {
            pcache.addToCache(node, eprime.toString());
        }
    }
    
    public class RetPair {
        public String expstring;
        public NodeReplacement nodereplace;
    }
    
    private String decorateExpressionString(ASTNode curnode, boolean shorttable, ArrayList<ASTNode> doms) {
        StringBuilder expbuf = new StringBuilder();
        
        if(!shorttable) {
            expbuf.append("Long|||");
        }
        else {
            expbuf.append("Short|||");
        }
        
        expbuf.append(curnode.toString());
        for(ASTNode a : doms) {
            expbuf.append("|||");
            expbuf.append(a);
        }
        
        return expbuf.toString();
    }
    
    public RetPair tryCache(ASTNode curnode, boolean shorttable) {
        ArrayList<ASTNode> varlist = getVariablesOrdered(curnode);
        ArrayList<ASTNode> domains = getDomains(varlist);
        
        RetPair ret = new RetPair();
        
        ret.expstring = decorateExpressionString(curnode, shorttable, domains);
        
        String cache = null;
        if(diskcache) {
            cache=pcache.findInCache(ret.expstring);
        }
        if(cache != null) {
            if(verbose) {
                System.out.println("Cache match!");
            }
            EPrimeReader epr = new EPrimeReader(cache, false);
            ArrayList<ASTNode> parsed = epr.readParameterFile(m);
            if (verbose) {
                System.out.println("Cache read!");
            }
            ASTNode tab = parsed.get(0).getChild(1);
            
            TransformSimplify ts=new TransformSimplify();
            tab=ts.transform(tab);
            
            tab=m.cmstore.newConstantMatrixDedup(tab);
            
            if(!shorttable) {
                ret.nodereplace = new NodeReplacement(new Table(m, CompoundMatrix.make(varlist), tab));
                return ret;
            }
            else {
                ret.nodereplace = new NodeReplacement(new TableShort(m, CompoundMatrix.make(varlist), tab));
                return ret;
            }
        }
        else {
            if(verbose) {
                System.out.println("Cache miss");
            }
        }
        return ret;
    }
    
    protected NodeReplacement processNode(ASTNode curnode)
	{
	    boolean shorttable=(CmdFlags.make_short_tab==2 || CmdFlags.make_short_tab==4);
	    if(curnode instanceof MakeTable) {
	        if(CmdFlags.make_short_tab==0) {
	            //  Option 0 given on command line. 
	            //  Just throw away the MakeTable function
	            return new NodeReplacement(curnode.getChild(0));
	        }
	        
	        //  Check the cache.
	        RetPair ret = tryCacheNormalised(curnode.getChild(0), shorttable);
	        if(ret.nodereplace != null) {
	            return ret.nodereplace;
            }
            
            //  First normalise  before generating table.
            ASTNode a=normalise(curnode.getChild(0));
            ASTNode newTable;
            
	        if(!shorttable) {
	            //  Make conventional table constraint
	            newTable = makeTableLong(a, Long.MAX_VALUE, Long.MAX_VALUE);
	        }
	        else {
	            //  Make short table constraint.
                newTable = makeTableShort(a, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE);
            }
            saveToCacheNormalised(ret.expstring, a, newTable);
            return new NodeReplacement(newTable);
        }
        else if(!prop && (CmdFlags.make_short_tab==3 || CmdFlags.make_short_tab==4) 
            && !(curnode instanceof And) && curnode.getParent()!=null && curnode.getParent().inTopAnd() 
            && !(curnode instanceof Tag) && curnode.isRelation()
            && !(curnode instanceof Table) && !(curnode instanceof TableShort) && !(curnode instanceof NegativeTable)
            && !(curnode instanceof BooleanConstant)) {
            //   Heuristic for applying the short table converter. 
            //   Only applied in final tailoring process.
            //   If curnode is directly contained in the top-level conjunction (or Top) then:
            //   Check top-level constraints for repeated variables, do short-table conversion with limit on short supports. 
            
            if(heuristic(curnode)) {
                if(verbose) {
                    System.out.println("Trying ct:"+curnode);
                }
                //  Check the cache.
                RetPair ret = tryCacheNormalised(curnode, shorttable);
                if(ret.nodereplace != null) {
                    return ret.nodereplace;
                }
                if(failCache.contains(ret.expstring)) {   /// Ideally this check should go above the pcache check.
                    return new NodeReplacement(new Tag(curnode));
                }
                
                ASTNode a = normalise(curnode);
                ASTNode newTable;
                if(CmdFlags.make_short_tab==3) {
                    newTable=makeTableLong(a, 10000, 100000);
                }
                else {
                    newTable=makeTableShort(a, 10000, 100000, 100000);
                }
                
                if(newTable==null) {
                    failCache.add(ret.expstring);
                    if(verbose) {
                        System.out.println("Adding to failCache:"+ret.expstring);
                    }
                    return new NodeReplacement(new Tag(curnode));
                }
                
                // Save in the cache
                saveToCacheNormalised(ret.expstring, a, newTable);
                return new NodeReplacement(newTable);
            }
        }
        return null;
    }
    
    private boolean heuristic(ASTNode curnode) {
        ArrayList<ASTNode> varlist=getVariablesOrdered(curnode);
        ArrayList<ASTNode> varlistdups=getVariablesDup(curnode);
        
        //  Duplicate variables and within a reasonable size bound.
        //  Should perhaps look at domain size rather than # variables.
        if(varlist.size()<varlistdups.size() && varlist.size()<=10) {
            if(verbose) {
                System.out.println("H1");
            }
            return true;
        }
        
        //  Tree size is much larger than the number of variables.
        //  Either the expression is highly complex or it contains a large 
        //  number of constants. 
        if(curnode.treesize() > 5*varlist.size()) {
            if(verbose) {
                System.out.println("H2");
            }
            return true;
        }
        
        // Constraint does not get GAC, and it overlaps with some other
        // constraint that does get GAC or similar. Uses the strongProp method. 
        if(!curnode.strongProp() && varlist.size()<=10) {
            ASTNode constraints=m.constraints.getChild(0);
            if(constraints instanceof And) {
                for(int i=0; i<constraints.numChildren(); i++) {
                    ASTNode c=constraints.getChild(i);
                    if(c.strongProp()) {
                        for(int varidx=0; varidx<varlist.size(); varidx++) {
                            if(c.contains(varlist.get(varidx))) {
                                if(verbose) {
                                    System.out.println("H3");
                                }
                                return true;
                            }
                        }
                    }
                }
            }
        }
        
        return false;
    }
    
    public ASTNode makeTableShort(ASTNode curnode, long suplimit, long faillimit, long impliedlimit) {
        TransformQuantifiedExpression tqe=new TransformQuantifiedExpression(m);
        curnode=tqe.transform(curnode);
        
        // Curnode must already be normalised.
        
        //  Use ordered to match the order used in the normalised load/store functions. 
        ArrayList<ASTNode> varlist=getVariablesOrdered(curnode);
        
        if(verbose) System.out.println("Expression:"+curnode);
        if(verbose) System.out.println("Variables:"+varlist);
        
        ArrayList<ASTNode> vardoms=getDomains(varlist);
        
        if(twowatchedlits) {
            setupShortSupports2(varlist.size(), vardoms);
        }
        else {
            setupShortSupports(varlist.size());
        }
        
        failcount=0;
        impliedcount=0;
        
        //  Break curnode down if it is a disjunction and all disjuncts are shorter than the full constraint. 
        boolean decompose=false;
        if(curnode instanceof Or) {
            decompose=true;
            for(int i=0; i<curnode.numChildren(); i++) {
                ArrayList<ASTNode> dvarlist=getVariablesOrdered(curnode.getChild(i));
                if(dvarlist.size()==varlist.size()) {
                    decompose=false;
                    break;
                }
            }
        }
        decompose=false;   // It doesn't work well.
        if(decompose) {
            if(verbose) System.out.println("Decomposing disjunction!");
            
            for(int i=0; i<curnode.numChildren(); i++) {
                if(verbose) System.out.println("Doing disjunct "+i+":"+curnode.getChild(i));
                // Find the variables in this disjunct and put them to the start of the variable order. 
                ArrayList<ASTNode> dvarlist=getVariablesOrdered(curnode.getChild(i));
                
                ArrayList<Long> assignment=new ArrayList<Long>(Collections.nCopies(varlist.size(), Long.MIN_VALUE));
                
                ArrayList<Integer> vo=new ArrayList<Integer>(varlist.size());
                for(int j=0; j<dvarlist.size(); j++) {
                    vo.add(varlist.indexOf(dvarlist.get(j)));
                }
                for(int j=0; j<varlist.size(); j++) {
                    if(vo.indexOf(j)==-1) {
                        vo.add(j);
                    }
                }
                
                //  Search with a depth limit of dvarlist.size, so only the variables in this disjunct will be assigned (even though vo will change during search)
                boolean flag=DFS(varlist, vardoms, curnode, curnode, assignment, vo, -1, suplimit, faillimit, impliedlimit, dvarlist.size());
                if(verbose) System.out.println("Stats of one disjunct: "+failcount+" "+impliedcount+" "+shortsups.size()+" "+flag);
                if(!flag) return null;
            }
        }
        else {
            // Do the normal search on all variables. 
            ArrayList<Long> assignment=new ArrayList<Long>(Collections.nCopies(varlist.size(), Long.MIN_VALUE));
            ArrayList<Integer> vo=new ArrayList<Integer>(varlist.size());
            for(int i=0; i<varlist.size(); i++) {
                vo.add(i);
            }
            
            boolean flag=DFS(varlist, vardoms, curnode, curnode, assignment, vo, -1, suplimit, faillimit, impliedlimit, Integer.MAX_VALUE);
            if(verbose) System.out.println("Stats: "+failcount+" "+impliedcount+" "+shortsups.size()+" "+flag);
            if(!flag) return null;
        }
        
        // Now convert into a tableshort constraint.
        
        ArrayList<ASTNode> shortsups2=new ArrayList<ASTNode>();
        
        for(int i=0; i<shortsups.size(); i++) {
            
            ArrayList<Long> shortsupold=shortsups.get(i);
            
            ArrayList<ASTNode> shortsupnew=new ArrayList<ASTNode>();
            
            for(int j=0; j<shortsupold.size(); j++) {
                if(shortsupold.get(j)!=Long.MIN_VALUE) {
                    shortsupnew.add(CompoundMatrix.make(NumberConstant.make(j+1), NumberConstant.make(shortsupold.get(j))));
                }
            }
            
            shortsups2.add(CompoundMatrix.make(shortsupnew));
        }
        
        ASTNode tab=CompoundMatrix.make(shortsups2);
        
        //  Further compress the set of short tuples using an extension of the IJCAI'13 algorithm.
        ArrayList<ArrayList<Intpair>> doms = new ArrayList<ArrayList<Intpair>>(vardoms.size());
        for(int i=0; i<vardoms.size(); i++) doms.add(vardoms.get(i).getIntervalSet());
        ASTNode compressed=TransformShortTableSquash.compressShortTab(tab, doms);
        
        if(compressed!=null) {
            CmdFlags.printlnIfVerbose("In: " + tab +"\n\n"+doms);
            CmdFlags.printlnIfVerbose("Out: " + compressed.numChildren());
            tab=compressed;
        }
        
        tab=m.cmstore.newConstantMatrixDedup(tab);
        
        return new TableShort(m, CompoundMatrix.make(varlist), tab);
    }
    
    public ASTNode makeTableLong(ASTNode curnode, long suplimit, long faillimit) {
        TransformQuantifiedExpression tqe=new TransformQuantifiedExpression(m);
        curnode=tqe.transform(curnode);
        
        //  Option 1 or 3 -- turn makeTable function into a table constraint.
        
        //  Should normalise here. 
        
        //  Use ordered version of getVariables because it should give the variables in the same
        //  order that the 'normalised' save/load functions expect. 
        ArrayList<ASTNode> varlist=getVariablesOrdered(curnode);
        ArrayList<ASTNode> vardoms=getDomains(varlist);
        
        setupShortSupports(varlist.size());
        
        failcount=0;
        boolean flag=DFSfull(varlist, vardoms, curnode, new ArrayList<Long>(), suplimit, faillimit);
        if(verbose && !flag) {
            System.out.println("DFS hit some limit.");
        }
        if(!flag) return null;
        
        ArrayList<ASTNode> shortsups2=new ArrayList<ASTNode>();
        
        for(int i=0; i<shortsups.size(); i++) {
            
            ArrayList<Long> shortsupold=shortsups.get(i);
            
            ArrayList<ASTNode> shortsupnew=new ArrayList<ASTNode>(varlist.size());
            
            for(int j=0; j<shortsupold.size(); j++) {
                shortsupnew.add(NumberConstant.make(shortsupold.get(j)));
            }
            
            shortsups2.add(CompoundMatrix.make(shortsupnew));
        }
        
        ASTNode tab=CompoundMatrix.make(shortsups2);
        
        tab=m.cmstore.newConstantMatrixDedup(tab);
        
        return new Table(m, CompoundMatrix.make(varlist), tab);
    }
    
    
    //   Assumes only decision variables and references to the constant matrices remain.
    public ArrayList<ASTNode> getVariablesDup(ASTNode exp) {
        ArrayList<ASTNode> tmp=new ArrayList<ASTNode>();
        getVariablesDupInner(exp, tmp);
        return tmp;
    }
    
    private void getVariablesDupInner(ASTNode exp, ArrayList<ASTNode> varset) {
        if(exp instanceof Identifier && exp.getCategory()>ASTNode.Constant) {
            // Collect all identifiers except those that refer to a constant matrix.
            varset.add(exp);
        }
        else {
            for(int i=0; i<exp.numChildren(); i++) {
                getVariablesDupInner(exp.getChild(i), varset);
            }
        }
    }
    
    //   Assumes only decision variables and references to the constant matrices remain.
    public ArrayList<ASTNode> getVariablesOrdered(ASTNode exp) {
        HashSet<ASTNode> tmp=new HashSet<ASTNode>();
        ArrayList<ASTNode> vars_ordered=new ArrayList<ASTNode>();
        getVariablesOrderedInner(exp, tmp, vars_ordered);
        return vars_ordered;
    }
    
    private void getVariablesOrderedInner(ASTNode exp, HashSet<ASTNode> varset, ArrayList<ASTNode> varlist) {
        if(exp instanceof Identifier && exp.getCategory()>ASTNode.Constant) {
            // Collect all identifiers except those that refer to a constant matrix.
            if(! varset.contains(exp)) {
                varset.add(exp);
                varlist.add(exp);
            }
        }
        else {
            for(int i=0; i<exp.numChildren(); i++) {
                getVariablesOrderedInner(exp.getChild(i), varset, varlist);
            }
        }
    }
    
    public ArrayList<ASTNode> getDomains(ArrayList<ASTNode> varlist) {
        ArrayList<ASTNode> vardoms=new ArrayList<ASTNode>();
        TransformSimplify ts=new TransformSimplify();
        for(int i=0; i<varlist.size(); i++) {
            vardoms.add(ts.transform(m.global_symbols.getDomain(varlist.get(i).toString())));
        }
        return vardoms;
    }
    
    //  Currently just sorts the associative-commutative expressions. 
    //  Copies the expression. 
    public ASTNode normalise(ASTNode a) {
        ASTNode b=a.copy();
        TransformNormaliseAlpha tn=new TransformNormaliseAlpha(m);
        b=tn.transform(b);
        return b;
    }
    
    // Normalise method to be used with the persistent cache.
    // Renames variables as a1, a2, a3
    public RetPair tryCacheNormalised(ASTNode b, boolean shorttable) {
        //  Should first sort any AC expressions into alphabetical order
        //  to put variables in a consistent order w.r.t their relative positions in a matrix.
        //  This version only replaces variable names. 
        ASTNode a=normalise(b);
        
        if(verbose) {
            CmdFlags.println("In tryCacheNormalised:"+a);
        }
        
        ArrayList<ASTNode> varlist=getVariablesOrdered(a);
        ArrayList<ASTNode> domains=getDomains(varlist);
        
        for(int i=0; i<varlist.size(); i++) {
            ReplaceASTNode r=new ReplaceASTNode(varlist.get(i), new Identifier(m, "xxxx_"+i));
            a=r.transform(a);
        }
        
        RetPair ret = new RetPair();
        
        ret.expstring = decorateExpressionString(a, shorttable, domains);
        
        ////////////////////////////////////////////////////////////////////////
        //
        //   Memory cache lookup
        
        ASTNode lookupMemCache=memCache.get(ret.expstring);
        if(lookupMemCache != null) {
            assert lookupMemCache instanceof Identifier;  //  Already stored in CM store. 
            if(verbose) {
                System.out.println("Memory cache read!");
            }
            if(!shorttable) {
                ret.nodereplace = new NodeReplacement(new Table(m, CompoundMatrix.make(varlist), lookupMemCache));
            }
            else {
                ret.nodereplace = new NodeReplacement(new TableShort(m, CompoundMatrix.make(varlist), lookupMemCache));
            }
            return ret;
        }
        
        ////////////////////////////////////////////////////////////////////////
        //
        //  Disc cache lookup
        
        String cache = null;
        if(diskcache) {
            cache=pcache.findInCache(ret.expstring);
        }
        if(cache != null) {
            if(verbose) {
                System.out.println("Cache match!");
            }
            EPrimeReader epr = new EPrimeReader(cache, false);
            ArrayList<ASTNode> parsed = epr.readParameterFile(m);
            if (verbose) {
                System.out.println("Cache read!");
            }
            ASTNode tab = parsed.get(0).getChild(1);
            
            TransformSimplify ts=new TransformSimplify();
            tab=ts.transform(tab);
            
            tab=m.cmstore.newConstantMatrixDedup(tab);
            
            memCache.put(ret.expstring, tab);
            
            if(!shorttable) {
                ret.nodereplace = new NodeReplacement(new Table(m, CompoundMatrix.make(varlist), tab));
            }
            else {
                ret.nodereplace = new NodeReplacement(new TableShort(m, CompoundMatrix.make(varlist), tab));
            }
            return ret;
        }
        else {
            if(verbose) {
                System.out.println("Cache miss");
            }
        }
        return ret;
    }
    
    //   Save to cache. curnode MUST be already normalised using the normalise function, 
    //   AND newTable MUST have its columns in the normalised order. 
    public void saveToCacheNormalised(String node, ASTNode curnode, ASTNode newTable) {
        // node is the string containing the expression and domains. We pass it in because we already
        // know it, and it is expensive to recalculate
        
        ArrayList<ASTNode> varlist=getVariablesOrdered(curnode);
        ArrayList<ASTNode> domains=getDomains(varlist);
        
        ASTNode a_copy=curnode.copy();
        
        for(int i=0; i<varlist.size(); i++) {
            ReplaceASTNode r=new ReplaceASTNode(varlist.get(i), new Identifier(m, "xxxx_"+i));
            a_copy=r.transform(a_copy);
        }
        
        if(node==null) {
            node = decorateExpressionString(a_copy, (newTable instanceof TableShort), domains);
        }
        
        StringBuilder eprime = new StringBuilder();
        eprime.append("letting tab" + PersistentCache.getHash(node) + " = ");
        
        ASTNode table=newTable.getChildConst(1);
        if(!(table.isMatrixLiteral())) {
            System.out.println(table.toString());
            CmdFlags.errorExit("Confused ShortTable");
        }
        
        eprime.append(table.toString());
        if(diskcache) {
            pcache.addToCache(node, eprime.toString());
        }
        
        //  Save to memory cache as well. 
        assert newTable.getChild(1) instanceof Identifier;
        memCache.put(node, newTable.getChild(1));
    }
    
    ////////////////////////////////////////////////////////////////////////////
    //
    //   Convert an expression to a short table.
    
    long failcount=0;
    long impliedcount=0;
    
    // Place justassignedidx at the start of varorder.
    void promoteIdx(ArrayList<Integer> varorder, int justassignedidx) {
        if(justassignedidx!=-1) {
            int voidx=varorder.indexOf(justassignedidx);
            
            int tmp=varorder.get(voidx);
            for(int i=voidx; i>0; i--) {
                varorder.set(i, varorder.get(i-1));
            }
            varorder.set(0, tmp);
        }
    }
    
    // exp is the local copy of the expression.
    // When this becomes true, generate a short support and continue.
    // When this becomes false, backtrack.
    private boolean DFS(ArrayList<ASTNode> varlist, ArrayList<ASTNode> vardoms, ASTNode orig_exp, ASTNode exp, ArrayList<Long> assignment, ArrayList<Integer> varorder, int justassignedidx, long suplimit, long faillimit, long impliedlimit, int depthlimit) {
        // Check the set of short supports to see if any cover the current assignment.
        if((!twowatchedlits) && checkShortSupports(assignment, justassignedidx)) {
            impliedcount++;
            return impliedcount<impliedlimit;  //  A short support has already been generated to cover this assignment.
        }
        if(twowatchedlits && checkShortSupports2(assignment, justassignedidx)) {
            impliedcount++;
            return impliedcount<impliedlimit;  //  A short support has already been generated to cover this assignment.
        }
        
        if(exp instanceof BooleanConstant) {
            // Change the variable ordering here.
            promoteIdx(varorder, justassignedidx);
            
            if(exp.getValue()==1) {
                // Generate a short support from the current assignment and add it to the set.
                if(!twowatchedlits) {
                    addShortSupport(varlist, orig_exp, assignment, justassignedidx);
                }
                else {
                    addShortSupport2(varlist, orig_exp, assignment, justassignedidx);
                }
                
                if(shortsups.size()>suplimit) {
                    return false;   ///  Check the limit.
                }
                return true;
            }
            else {
                failcount++;
                return failcount<faillimit;   // continue search iff failcount<=faillimit.
            }
        }
        
        // check depthlimit
        if(depthlimit<assignment.size()) {
            int countassign=0;
            for(int i=0; i<assignment.size(); i++) {
                if(assignment.get(i)!=Long.MIN_VALUE) countassign++;
            }
            if(countassign>=depthlimit) {
                return true;
            }
        }
        
        //  Iterate through the domain of the current variable assigning each value in turn.
        int curvaridx=-1;
        varorderloop:
        for(int i=0; i<varorder.size(); i++) {
            if(assignment.get(varorder.get(i))==Long.MIN_VALUE) {
                curvaridx=varorder.get(i);
                break varorderloop;
            }
        }
        
        ASTNode curvar=varlist.get(curvaridx);
        TransformSimplify ts=new TransformSimplify();
        
        if(!twowatchedlits) {
            ArrayList<Intpair> vals=vardoms.get(curvaridx).getIntervalSet();
            for(int i=0; i<vals.size(); i++) {
                for(long val=vals.get(i).lower; val<=vals.get(i).upper; val++) {
                    ASTNode local_exp=exp.copy();
                    
                    local_exp=assignValue(local_exp, curvar, val);
                    
                    local_exp=ts.transform(local_exp);  // make the assignment and simplify.
                    
                    assignment.set(curvaridx, val);
                    
                    boolean flag=DFS(varlist, vardoms, orig_exp, local_exp, assignment, varorder, curvaridx, suplimit, faillimit, impliedlimit, depthlimit);
                    if(!flag) return false;
                    
                    assignment.set(curvaridx, Long.MIN_VALUE);   //  delete this assignment.
                }
            }
        }
        else {
            //  Two watched literals in use. Search using domains.
            long[] init_dom=initial_domains.get(curvaridx);
            boolean[] cur_dom=current_domains.get(curvaridx);
            for(int i=0; i<init_dom.length; i++) {
                if(cur_dom[i]) {
                    ASTNode local_exp=exp.copy();
                    
                    local_exp=assignValue(local_exp, curvar, init_dom[i]);
                    
                    local_exp=ts.transform(local_exp);  // make the assignment and simplify.
                    
                    assignment.set(curvaridx, init_dom[i]);
                    
                    BTMark();
                    
                    assign(curvaridx, i);
                    
                    boolean flag=DFS(varlist, vardoms, orig_exp, local_exp, assignment, varorder, curvaridx, suplimit, faillimit, impliedlimit, depthlimit);
                    
                    assignment.set(curvaridx, Long.MIN_VALUE);   //  delete this assignment.
                    BTRevert();
                    unassign(curvaridx);
                    
                    if(!flag) return false;
                }
            }
        }
        return true;
    }
    
    //   List of short supports, and one-watched-literal data structures.
    //   The one watched literal indicates a literal that is either false or unknown.
    
    private ArrayList<ArrayList<Long>> shortsups;   //  Wildcard is Long.MIN_VALUE
    private ArrayList<ArrayList<Integer>> var_to_watched_sup;   ///   map from variable number to list of shortsups that are watching that variable.
    
    private void setupShortSupports(int numvars) {
        shortsups=new ArrayList<ArrayList<Long>>();
        var_to_watched_sup=new ArrayList<ArrayList<Integer>>();
        for(int i=0; i<numvars; i++) {
            var_to_watched_sup.add(new ArrayList<Integer>());
        }
    }
    
    private void addShortSupport(ArrayList<ASTNode> varlist, ASTNode orig_exp, ArrayList<Long> assignment, int curvar) {
        ArrayList<Long> sup=new ArrayList<Long>(assignment);   //copy
        
        ArrayList<Integer> assignedIdx=new ArrayList<Integer>();
        for(int i=0; i<sup.size(); i++) {
            if(sup.get(i)!=Long.MIN_VALUE) {
                assignedIdx.add(i);
            }
        }
        
        shortSupportMinDivideConquer(0, assignedIdx.size(), assignedIdx, varlist, orig_exp, sup, curvar); 
        
        //shortSupportMinLinear(varlist, orig_exp, sup, curvar);
        
        assert sup.get(curvar)!=Long.MIN_VALUE;
        
        // No need to extend sup to full length.  Short sups can contain tuples that are truncated on the right.
        if(verbose) {
            System.out.print("Adding short support: ");
            for(int i=0; i<sup.size(); i++) {
                System.out.print((sup.get(i)!=Long.MIN_VALUE?String.valueOf(sup.get(i)):"*")+" ");
            }
            System.out.println(" " + failcount+" "+impliedcount + " "+shortsups.size());
        }
        
        shortsups.add(sup);
        var_to_watched_sup.get(curvar).add(shortsups.size()-1);
    }
    
    // Different method of minimising the short support -- divide and conquer
    // can eliminate 1/2, 1/4  etc of all elements in one test. 
    //  sup is changed in place. 
    // assignedIdx gives the indices in sup of the assignments made by the DFS alg. This allows neat binary splits on assignedIdx without worrying about gaps in sup. 
    private void shortSupportMinDivideConquer(int lower, int upper, ArrayList<Integer> assignedIdx, ArrayList<ASTNode> varlist, ASTNode local_exp, ArrayList<Long> sup, int curvar) {
        // Base case. Only one element remaining. local_exp is NOT True so we must need this final element in sup. Just return true. 
        if(upper==lower+1) {
            return;
        }
        
        TransformSimplify ts=new TransformSimplify();
        
        //  Divide into [lower..mid-1],  [mid..upper-1]  inclusive. 
        int mid=(upper-lower)/2+lower;
        
        // Left branch.
        // Assign the literals from mid..upper-1.
        // If this assignment leads to True, then can eliminate everything in the left interval. 
        ASTNode left_exp=local_exp.copy();
        for(int i=mid; i<upper; i++) {
            int varidx=assignedIdx.get(i);
            if(sup.get(varidx)!=Long.MIN_VALUE) {
                assignValue(left_exp, varlist.get(varidx), sup.get(varidx));
            }
        }
        left_exp=ts.transform(left_exp);
        if(left_exp instanceof BooleanConstant) {
            assert left_exp.getValue()==1;
            // Eliminate everything on the left.
            for(int i=lower; i<mid; i++) {
                int varidx=assignedIdx.get(i);
                sup.set(varidx, Long.MIN_VALUE);
            }
        }
        else {
            shortSupportMinDivideConquer(lower, mid, assignedIdx, varlist, left_exp, sup, curvar);
        }
        
        // Right branch. 
        // Assign literals from lower to mid-1.
        ASTNode right_exp=local_exp.copy();
        for(int i=lower; i<mid; i++) {
            int varidx=assignedIdx.get(i);
            if(sup.get(varidx)!=Long.MIN_VALUE) {
                assignValue(right_exp, varlist.get(varidx), sup.get(varidx));
            }
        }
        right_exp=ts.transform(right_exp);
        if(right_exp instanceof BooleanConstant) {
            assert right_exp.getValue()==1;
            // Eliminate everything on the right.
            for(int i=mid; i<upper; i++) {
                int varidx=assignedIdx.get(i);
                sup.set(varidx, Long.MIN_VALUE);
            }
        }
        else {
            shortSupportMinDivideConquer(mid, upper, assignedIdx, varlist, right_exp, sup, curvar);
        }
    }
    
    private void shortSupportMinLinear(ArrayList<ASTNode> varlist, ASTNode orig_exp, ArrayList<Long> sup, int curvar) {
        for(int i=0; i<sup.size(); i++) {
            if(i!=curvar && sup.get(i)!=Long.MIN_VALUE) {
                //  For all but the last assignment in sup, try removing it and see if the expression still evaluates to true.
                
                ASTNode local_exp=orig_exp.copy();
                
                for(int j=0; j<sup.size(); j++) {
                    if(j!=i && sup.get(j)!=Long.MIN_VALUE) {
                        // j is not a wildcard, and not the one we are testing. 
                        local_exp=assignValue(local_exp, varlist.get(j), sup.get(j));
                    }
                }
                
                TransformSimplify ts=new TransformSimplify();
                local_exp=ts.transform(local_exp);
                
                if(local_exp instanceof BooleanConstant) {
                    assert local_exp.getValue()==1;
                    sup.set(i, Long.MIN_VALUE);  //  Set variable i to the wildcard value.
                }
            }
        }
    }
    
    private ASTNode assignValue(ASTNode exp, ASTNode var, long val) {
        ReplaceASTNode r1;
        if(var.isRelation()) {
            r1=new ReplaceASTNode(var, new BooleanConstant(val==1));
        }
        else {
            r1=new ReplaceASTNode(var, NumberConstant.make(val));
        }
        return r1.transform(exp);  // make the assignment, probably destructive on exp. 
    }
    
    private boolean checkShortSupports(ArrayList<Long> assignment, int curvar) {
        if(curvar==-1) {
            return false;
        }
        
        long val=assignment.get(curvar);
        
        //   Check watches of "false or unknown" literals in short supports.
        
        ArrayList<Integer> watchlist=var_to_watched_sup.get(curvar);
        
        for(int i=watchlist.size()-1; i>=0; i--) {
            int shortsup_index=watchlist.get(i);
            
            ArrayList<Long> shortsup=shortsups.get(shortsup_index);
            
            assert shortsup.get(curvar)!=Long.MIN_VALUE;
            
            if(shortsup.get(curvar)==val) {
                //  This literal is true...  loop through the short support to find another that is false or unknown. 
                
                boolean litfound=false;
                for(int j=curvar+1; j<shortsup.size() && !litfound; j++) {
                    if(shortsup.get(j)!=Long.MIN_VALUE && ( assignment.get(j)==Long.MIN_VALUE || shortsup.get(j)!=assignment.get(j))) {
                        // This is a non-wildcard false or unknown literal. 
                        litfound=true;
                        
                        //  Remove this shortsup from its current watchlist.
                        watchlist.set(i, watchlist.get(watchlist.size()-1));
                        watchlist.remove(watchlist.size()-1);
                        
                        // Insert into the other watchlist.
                        var_to_watched_sup.get(j).add(shortsup_index);
                    }
                }
                
                for(int j=0; j<curvar && !litfound; j++) {
                    if(shortsup.get(j)!=Long.MIN_VALUE && ( assignment.get(j)==Long.MIN_VALUE || shortsup.get(j)!=assignment.get(j))) {
                        // This is a non-wildcard false or unknown literal. 
                        litfound=true;
                        
                        //  Remove this shortsup from its current watchlist.
                        watchlist.set(i, watchlist.get(watchlist.size()-1));
                        watchlist.remove(watchlist.size()-1);
                        
                        // Insert into the other watchlist.
                        var_to_watched_sup.get(j).add(shortsup_index);
                    }
                }
                
                if(!litfound) {
                    // This short support evaluates to true.
                    return true;
                }
            }
        }
        
        return false;
    }
    
    ////////////////////////////////////////////////////////////////////////////
    //
    //  Second version of short support handling, this one with domains and 
    //  2-watched lits
    
    ArrayList<boolean[]> current_domains;
    ArrayList<long[]> initial_domains;
    ArrayList<Integer> domsize;
    
    ArrayList<Integer> watch1;
    ArrayList<Integer> watch2;
    
    private void setupShortSupports2(int numvars, ArrayList<ASTNode> vardoms) {
        
        initial_domains=new ArrayList<long[]>();
        current_domains=new ArrayList<boolean[]>();
        
        backtrack_var=new ArrayList<Integer>();
        backtrack_val=new ArrayList<Integer>();
        
        search_assignments=new ArrayList<Integer>();
        
        watch1=new ArrayList<Integer>();
        watch2=new ArrayList<Integer>();
        
        domsize=new ArrayList<Integer>();
        
        for(int i=0; i<numvars; i++) {
            ArrayList<Intpair> dom=vardoms.get(i).getIntervalSet();
            long[] allValues=new long[(int)Intpair.numValues(dom)];
            boolean[] valuesIn=new boolean[allValues.length];
            int cur=0;
            for(int j=0; j<dom.size(); j++) {
                for(long val=dom.get(j).lower; val<=dom.get(j).upper; val++) {
                    allValues[cur]=val;
                    valuesIn[cur]=true;
                    cur++;
                }
            }
            initial_domains.add(allValues);
            current_domains.add(valuesIn);
            domsize.add(allValues.length);
        }
        
        shortsups=new ArrayList<ArrayList<Long>>();
        var_to_watched_sup=new ArrayList<ArrayList<Integer>>();
        for(int i=0; i<numvars; i++) {
            var_to_watched_sup.add(new ArrayList<Integer>());
        }
    }
    
    private void addShortSupport2(ArrayList<ASTNode> varlist, ASTNode orig_exp, ArrayList<Long> assignment, int curvar) {
        
        ArrayList<Long> sup=new ArrayList<Long>(assignment);   //copy
        
        ArrayList<Integer> assignedIdx=new ArrayList<Integer>();
        for(int i=0; i<sup.size(); i++) {
            if(sup.get(i)!=Long.MIN_VALUE) {
                assignedIdx.add(i);
            }
        }
        
        shortSupportMinDivideConquer(0, assignedIdx.size(), assignedIdx, varlist, orig_exp, sup, curvar); 
        
        //shortSupportMinLinear(varlist, orig_exp, sup, curvar);
        
        assert sup.get(curvar)!=Long.MIN_VALUE;
        
        // No need to extend sup to full length.  Short sups can contain tuples that are truncated on the right.
        if(verbose) {
            System.out.print("Adding short support: ");
            for(int i=0; i<sup.size(); i++) {
                System.out.print((sup.get(i)!=Long.MIN_VALUE?String.valueOf(sup.get(i)):"*")+" ");
            }
            System.out.println(" " + failcount+" "+impliedcount + " "+shortsups.size());
        }
        
        int supidx=shortsups.size();
        shortsups.add(sup);
        
        //  Watch the most recently assigned var
        var_to_watched_sup.get(curvar).add(supidx);
        watch1.add(curvar);
        
        //  Scan back in the assignment order to find the next var to watch.
        
        for(int trailidx=search_assignments.size()-1; trailidx>=0; trailidx--) {
            int btvar=search_assignments.get(trailidx);
            
            if(btvar!=curvar && sup.size()>btvar && sup.get(btvar)!=Long.MIN_VALUE) {
                var_to_watched_sup.get(btvar).add(supidx);
                watch2.add(btvar);
                return;
            }
        }
        
        assert sup.size()==1;
        var_to_watched_sup.get(curvar).add(supidx);
        watch2.add(curvar);  //  Just watch curvar twice in this case.
    }
    
    //  Propagate short supports. 
    private boolean checkShortSupports2(ArrayList<Long> assignment, int in_curvar) {
        if(in_curvar==-1) {
            return false;
        }
        
        //  Make a queue of variables that have changed. 
        ArrayList<Integer> q=new ArrayList<Integer>(); 
        boolean[] in_q=new boolean[assignment.size()];
        
        in_q[in_curvar]=true;
        q.add(in_curvar);
        
        //   Check watches of "false or unknown" literals in short supports.
        
        while(q.size()>0) {
            int curvar=q.remove(q.size()-1);
            in_q[curvar]=false;
            
            //  Iterate through the watchlist for curvar. 
            ArrayList<Integer> watchlist=var_to_watched_sup.get(curvar);
            
            for(int i=watchlist.size()-1; i>=0; i--) {
                int shortsup_index=watchlist.get(i);
                
                ArrayList<Long> shortsup=shortsups.get(shortsup_index);
                
                //  Check the short support does contain curvar.
                assert shortsup.get(curvar)!=Long.MIN_VALUE;
                
                int validx=Arrays.binarySearch(initial_domains.get(curvar), shortsup.get(curvar));
                
                //  Is the literal true, as opposed to false or unknown?
                if(current_domains.get(curvar)[validx] && domsize.get(curvar)==1) {
                    //  This literal is true...  loop through the short support to find another that is false or unknown. 
                    // Update watches.
                    
                    int w1=watch1.get(shortsup_index);  //  Which variables are watched by this short support?
                    int w2=watch2.get(shortsup_index);
                    
                    if(w1==curvar) {
                        // Update w1.  Avoid w2.
                        boolean litfound=false;
                        for(int j=0; j<shortsup.size(); j++) {
                            if(j!=w2 && shortsup.get(j)!=Long.MIN_VALUE) {
                                //  Get the value in the short sup
                                long val=shortsup.get(j);
                                int validxj=Arrays.binarySearch(initial_domains.get(j), val);
                                
                                //  val is either false (removed) or unknown
                                if((!current_domains.get(j)[validxj]) || domsize.get(j)>1) {
                                    w1=j;
                                    litfound=true;
                                    break;
                                }
                            }
                        }
                        
                        if(!litfound) {
                            //  w2 points to the only literal that might falsify this short support. 
                            //  Prune the value if it is present. 
                            int validxprune=Arrays.binarySearch(initial_domains.get(w2), shortsup.get(w2));
                            if(current_domains.get(w2)[validxprune]) {
                                pruneValidx(w2, validxprune);
                                if(domsize.get(w2)==0) {
                                    //  Domain wipeout -- however var w1 is assigned, it will cause some short support to evaluate to true.
                                    return true;
                                }
                                if(!in_q[w2]) {
                                    in_q[w2]=true;
                                    q.add(w2);
                                }
                            }
                        }
                        else {
                            //  Update data structures to set w1. 
                            watch1.set(shortsup_index, w1);
                            //  Remove this shortsup from its current watchlist.
                            watchlist.set(i, watchlist.get(watchlist.size()-1));
                            watchlist.remove(watchlist.size()-1);
                            
                            // Insert into the other watchlist.
                            var_to_watched_sup.get(w1).add(shortsup_index);
                        }
                    }
                    
                    if(w2==curvar) {
                        //  w2 is the watch that is on curvar (or they both were)
                        //  Update w2, avoiding w1.
                        //  If w2 can have no value, then prune the value at w1. 
                        
                        boolean litfound=false;
                        for(int j=0; j<shortsup.size(); j++) {
                            if(j!=w1 && shortsup.get(j)!=Long.MIN_VALUE) {
                                //  Get the value in the short sup
                                long val=shortsup.get(j);
                                int validxj=Arrays.binarySearch(initial_domains.get(j), val);
                                
                                //  val is either false (removed) or unknown
                                if((!current_domains.get(j)[validxj]) || domsize.get(j)>1) {
                                    w2=j;
                                    litfound=true;
                                    break;
                                }
                            }
                        }
                        
                        if(!litfound) {
                            //  w1 points to the only literal that can satisfy this short support. 
                            //  Prune the value if it is present. 
                            int validxprune=Arrays.binarySearch(initial_domains.get(w1), shortsup.get(w1));
                            if(current_domains.get(w1)[validxprune]) {
                                pruneValidx(w1, validxprune);
                                if(domsize.get(w1)==0) {
                                    //  Domain wipeout -- however var w1 is assigned, it will cause some short support to evaluate to true.
                                    return true;
                                }
                                if(!in_q[w1]) {
                                    in_q[w1]=true;
                                    q.add(w1);
                                }
                            }
                        }
                        else {
                            //  Update data structures to set w2. 
                            watch2.set(shortsup_index, w2);
                            
                            if(watchlist.size()>i && watchlist.get(i)==shortsup_index) {
                                //  Remove this shortsup from its current watchlist.
                                //  if it was not already removed when moving w1 (i.e. w1 and w2 were equal).
                                watchlist.set(i, watchlist.get(watchlist.size()-1));
                                watchlist.remove(watchlist.size()-1);
                            }
                            
                            // Insert into the other watchlist.
                            var_to_watched_sup.get(w2).add(shortsup_index);
                        }
                    }
                }
            }
        }
        
        return false;
    }
    
    ArrayList<Integer> backtrack_var;   //  For restoring the domains.
    ArrayList<Integer> backtrack_val;
    
    ArrayList<Integer> search_assignments;  //  Assignments made by the search procedure in order they were made. Just var idx. 
    
    private void BTMark() {
        backtrack_var.add(null);
        backtrack_val.add(null);
    }
    
    private void BTRevert() {
        while(backtrack_var.size()>0) {
            Integer btvar=backtrack_var.remove(backtrack_var.size()-1);
            Integer btvalidx=backtrack_val.remove(backtrack_val.size()-1);
            if(btvar==null) {
                return;
            }
            
            //  Restore val to var.
            current_domains.get(btvar)[btvalidx]=true;
            domsize.set(btvar, domsize.get(btvar)+1);
        }
    }
    
    //  Prune a value and add backtrack record. 
    private boolean prune(int var, long val) {
        int validx=Arrays.binarySearch(initial_domains.get(var), val);
        return pruneValidx(var,validx);
    }
    
    private boolean pruneValidx(int var, int validx) {
        if(current_domains.get(var)[validx]) {
            current_domains.get(var)[validx]=false;
            backtrack_var.add(var);
            backtrack_val.add(validx);
            
            domsize.set(var, domsize.get(var)-1);
            return domsize.get(var) > 0;
        }
        return true;
    }
    
    private void assign(int var, int validx) {
        boolean[] curdom=current_domains.get(var);
        
        assert curdom[validx];
        
        for(int i=0; i<curdom.length; i++) {
            if(i!=validx && curdom[i]) {
                assert pruneValidx(var, i);
            }
        }
        assert domsize.get(var)==1;
        
        search_assignments.add(var);
    }
    
    private void unassign(int var) {
        int var2=search_assignments.remove(search_assignments.size()-1);
        assert var==var2;
    }
    
    ////////////////////////////////////////////////////////////////////////////
    //
    //    Convert to full-length table
    
    private boolean DFSfull(ArrayList<ASTNode> varlist, ArrayList<ASTNode> vardoms, ASTNode exp, ArrayList<Long> assignment, long suplimit, long faillimit) {
        int depth=assignment.size();
        
        if(exp instanceof BooleanConstant) {
            if(depth==varlist.size() && exp.getValue()==1) {
                // Copy the current assignment into shortsups.
                shortsups.add(new ArrayList<Long>(assignment));
                if(verbose && shortsups.size()>suplimit) {
                    System.out.println("DFS hit supports limit");
                }
                return shortsups.size()<=suplimit; //  Continue search iff within sup limit.
            }
            if(exp.getValue()==0) {
                failcount++;
                if(verbose && failcount>faillimit) {
                    System.out.println("DFS hit fail limit");
                }
                return failcount<=faillimit;   // continue search iff failcount<=faillimit.
            }
            // When the expression evaluates to true but assignment is not long enough, continue forwardtracking.
        }
        
        //  Iterate through the domain of the current variable assigning each value in turn.
        ASTNode curvar=varlist.get(depth);
        ArrayList<Intpair> vals=vardoms.get(depth).getIntervalSet();
        TransformSimplify ts=new TransformSimplify();
        for(int i=0; i<vals.size(); i++) {
            for(long val=vals.get(i).lower; val<=vals.get(i).upper; val++) {
                ASTNode local_exp=exp.copy();
                
                local_exp=assignValue(local_exp, curvar, val);
                
                local_exp=ts.transform(local_exp);  // make the assignment and simplify.
                
                assignment.add(val);
                
                boolean flag=DFSfull(varlist, vardoms, local_exp, assignment, suplimit, faillimit);
                if(!flag) return false;
                
                assignment.remove(assignment.size()-1);   //  delete this assignment.
            }
        }
        return true;
    }
}
