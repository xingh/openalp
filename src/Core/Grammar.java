/**
 *  This file is part of OpenALP.
 *
 *  OpenALP is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  OpenALP is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with OpenALP.  If not, see <a href='http://www.gnu.org/licenses/'>http://www.gnu.org/licenses/</a>.
 *
 *  Grammar allows the dynamic manipulation of the grammar, and includes the
 *  methods required to test a sentance against the derived grammar.
 *
 *  @author      Adam Scarr
 *  @author      Rowan Spence
 *  @since       r1
 **/

package Core;

import Graph.*;
import java.util.LinkedList;

public class Grammar {
	private Node<GrammarNode, GrammarEdge> start, end;
	private Graph<GrammarNode, GrammarEdge> graph;
    private Tokenizer tokenizer;
	private int totalSentences;

	//----------------------------------------
	// Constructors
	//----------------------------------------
	public Grammar(LexiconDAO lexicon) {
		graph = new Graph<GrammarNode, GrammarEdge>();
		start = graph.createNode(new GrammarNode(new Token("START", "START", false, false, false, false, false, false)));
		start.lock();
		end = graph.createNode(new GrammarNode(new Token("END", "END", false, false, false, false, false, false)));
        tokenizer = new Tokenizer(lexicon);
		totalSentences = 0;
	}

	//----------------------------------------
	// Simple getters
	//----------------------------------------
	public Node<GrammarNode, GrammarEdge> getStart() {
		return start;
	}

	public Node<GrammarNode, GrammarEdge> getEnd() {
		return end;
	}

	public Graph getGraph() {
		return graph;
	}

	public int getTotalSentences(){
		return totalSentences;
	}

	//----------------------------------------
	// Non mutating logic
	//----------------------------------------
	// Checks if a given set of tokens is valid (there is a path from start to end.
	public float validateSentance(Sentance input) {
        graph.lockNodesRO();
        // TODO: Ask Dimitry about this, dosent seem right to need to create a new list just so
        //       Java knows that all elements implement a given interface.
        LinkedList<NodeFilter<GrammarNode>> filterList = new LinkedList<NodeFilter<GrammarNode>>(input);
        LinkedList<Node<GrammarNode, GrammarEdge>> path = start.getMatchedPath(filterList);

        // First check the path actually made it to the end...
        float validity = 1;
        if(path.size() > 0 && path.getLast().hasChild(end)) {
            // Walk the path and calculate the validity.
            Node last = null;
            for(Node<GrammarNode, GrammarEdge> node: path) {
                Edge edge = node.getEdgeTo(last);

                if(edge != null) {
                    validity *= edge.getEdgeStrength();
                }

                last = node;
            }

        } else {
            validity = 0;
        }
        
        graph.unlockNodesRO();
        return validity;
	}

    /**
     * Calculates the validity of each tokenized sentance in the set
     * and returns the validity of the best.
     * @param sentances The tokenized sentances.
     * @return  The validity of the most-valid sentance.
     */
    public float validateSentances(LinkedList<Sentance> sentances) {
        float best = Float.NEGATIVE_INFINITY;

        for(Sentance sentance: sentances) {
            float validity = validateSentance(sentance);
            if(validity > best) {
                best = validity;
            }
        }

        return best;
    }

	public float calculateSentanceValidity(String sentance) {
		return validateSentances(tokenizer.tokenize(sentance.toLowerCase()));
	}

	//----------------------------------------
	// Mutators
	//----------------------------------------

	// Returns true if given sentance is valid.
	// Returns false if its not, and adds it to the grammar.
	public boolean parse(String s) {

		LinkedList<Sentance> sentances = tokenizer.tokenize(s.toLowerCase());

        // Check if there are any valid sentances that match this structure.
        boolean exists = false;
        Sentance bestSentance = null;
        float bestValidity = Float.NEGATIVE_INFINITY;
        for(Sentance sentance: sentances) {
            float validity = validateSentance(sentance);
            if(validity > bestValidity) {
                bestValidity = validity;
                bestSentance = sentance;
            }

            if(validity > 0.0) {
                exists = true;
                addPath(sentance);
            }
        }

        if(!exists) {
            addPath(bestSentance);
        }

		// Simple grammar add.

        return exists;
    }

    // Creates add the given tokens to the grammar, creating a few nodes as possible.
    // It works by matching as many tokens as possible then when it finds a non-matching
    // token it creates the shortest chain possible to re-attach to the graph.
    public boolean addPath(Sentance tokens) {
        graph.lockNodesRW();
        totalSentences++;
        Node<GrammarNode, GrammarEdge> pathStart = start;
        Node<GrammarNode, GrammarEdge> fork;
        Node<GrammarNode, GrammarEdge> merge;
        Node<GrammarNode, GrammarEdge> lastNode = null;
        LinkedList<Node<GrammarNode, GrammarEdge>> matchedPath;
        LinkedList<Node<GrammarNode, GrammarEdge>> chain;

        do {
            // Consume as many tokens as we can.
            if(tokens.getFirst().matches(pathStart.getData())) {
                tokens.removeFirst();
            }
            LinkedList<NodeFilter<GrammarNode>> filterList = new LinkedList<NodeFilter<GrammarNode>>(tokens);
            matchedPath = pathStart.getMatchedPath(filterList);

            // Remove them from the token list and strengthen the paths.
            for(Node<GrammarNode, GrammarEdge> node: matchedPath) {
                Edge<GrammarNode, GrammarEdge> edge = node.getEdgeTo(lastNode);
                if(edge != null) {
                    edge.getData().incrementUsageCount();
                }
                if(node != start && node != end && tokens.size() > 0) {
                    tokens.removeFirst();
                }
                lastNode = node;
            }

            if(matchedPath.size() == 0) {
                fork = pathStart;
            } else {
                fork = matchedPath.getLast();
            }

            // Find the point we can merge back into the graph, and build our chain.
            chain = new LinkedList<Node<GrammarNode, GrammarEdge>>();
            lastNode = null;
            merge = null;
            Node<GrammarNode, GrammarEdge> node;
            boolean diverge = false;
            for(Token token: tokens)
            {
                // If a conjunction appears in a chain then it can only rejoin the graph
                // when a sentance terminator appears.
                if(token.getType().equals("CONJ")) {
                    diverge = true;
                }
                
                if(diverge) {
                    if(token.getType().equals("PERIOD")) {
                        merge = fork.findMatchingNode(token);
                    } else {
                        merge = null;
                    }
                } else {
                    merge = fork.findMatchingNode(token);
                }

                if(merge != null) {
                    break;
                }

                node = graph.createNode(new GrammarNode(token));
                if(lastNode != null) {
                    lastNode.connectsTo(node, new GrammarEdge(this));
                }

                chain.add(node);

                lastNode = node;

            }


            // Remove the used tokens.
            int i = 0;
            while (i < chain.size()) {
                tokens.removeFirst();
                i++;
            }

            if(merge == null) merge = end;

            // Connect the chain to the graph.
            if(chain.size() > 0) {
                fork.connectsTo(chain.getFirst(), new GrammarEdge(this));
                chain.getLast().connectsTo(merge, new GrammarEdge(this));
            } else {
                fork.connectsTo(merge, new GrammarEdge(this));
            }
            pathStart = merge;

        } while(tokens.size() > 0);

        graph.unlockNodesRW();
        return true;
    }

	public void clear() {
		graph.clear();
		start = graph.createNode(new GrammarNode(new Token("START", "START", false, false, false, false, false, false)));
		start.lock();
		end = graph.createNode(new GrammarNode(new Token("END", "END", false, false, false, false, false, false)));
		totalSentences = 0;
	}

}
