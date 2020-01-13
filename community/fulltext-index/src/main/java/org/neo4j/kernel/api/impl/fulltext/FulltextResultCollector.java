/*
 * Copyright (c) 2002-2020 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel.api.impl.fulltext;

import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.search.Collector;
import org.apache.lucene.search.LeafCollector;
import org.apache.lucene.search.Scorable;
import org.apache.lucene.search.ScoreMode;
import org.eclipse.collections.api.block.procedure.primitive.LongFloatProcedure;

import java.io.IOException;
import java.util.NoSuchElementException;
import java.util.PriorityQueue;

import org.neo4j.kernel.api.impl.index.collector.ValuesIterator;

class FulltextResultCollector implements Collector
{
    private final PriorityQueue<EntityScorePriorityQueue> results;
    private ScoredEntitySet currentCollector;

    FulltextResultCollector()
    {
        results = new PriorityQueue<>();
    }

    public ValuesIterator iterator()
    {
        if ( currentCollector == null )
        {
            return ValuesIterator.EMPTY;
        }
        currentCollector.collectionFinished( results );

        return new EntityResultsIterator( results );
    }

    @Override
    public LeafCollector getLeafCollector( LeafReaderContext context ) throws IOException
    {
        if ( currentCollector != null )
        {
            currentCollector.collectionFinished( results );
        }
        return currentCollector = new ScoredEntitySet( context );
    }

    @Override
    public ScoreMode scoreMode()
    {
        return ScoreMode.COMPLETE;
    }

    static class ScoredEntitySet implements LeafCollector
    {
        private final EntityScorePriorityQueue pq;
        private final NumericDocValues values;
        private Scorable scorer;

        ScoredEntitySet( LeafReaderContext context ) throws IOException
        {
            LeafReader reader = context.reader();
            values = reader.getNumericDocValues( LuceneFulltextDocumentStructure.FIELD_ENTITY_ID );
            pq = new EntityScorePriorityQueue( reader.maxDoc() );
        }

        @Override
        public void setScorer( Scorable scorer )
        {
            this.scorer = scorer;
        }

        @Override
        public void collect( int doc ) throws IOException
        {
            assert scorer.docID() == doc;
            if ( values.advanceExact( doc ) )
            {
                float score = scorer.score();
                long entityId = values.longValue();
                pq.insert( entityId, score );
            }
            else
            {
                throw new RuntimeException( "No document value for document id " + doc + "." );
            }
        }

        public void collectionFinished( PriorityQueue<EntityScorePriorityQueue> results )
        {
            if ( !pq.isEmpty() )
            {
                results.add( pq );
            }
        }
    }

    /**
     * Organise entity ids by decreasing scores, using a binary heap.
     * The implementation of the priority queue algorithm follows the one in Algorithms, 4th Edition by Robert Sedgewick and Kevin Wayne.
     */
    static class EntityScorePriorityQueue implements Comparable<EntityScorePriorityQueue>
    {
        private static final int ROOT = 1; // Root of the heap is always at index 1.
        private long[] entities;
        private float[] scores;
        private int size;

        EntityScorePriorityQueue( int capacity )
        {
            entities = new long[capacity + 1];
            scores = new float[capacity + 1];
        }

        @Override
        public int compareTo( EntityScorePriorityQueue o )
        {
            return Float.compare( o.scores[ROOT], scores[ROOT] );
        }

        public int size()
        {
            return size;
        }

        public boolean isEmpty()
        {
            return size == 0;
        }

        public void insert( long entityId, float score )
        {
            size += 1;
            entities[size] = entityId;
            scores[size] = score;
            liftTowardsRoot( size );
        }

        public void removeMax( LongFloatProcedure receiver )
        {
            receiver.value( entities[ROOT], scores[ROOT] );
            swap( ROOT, size );
            size--;
            pushTowardsBottom( ROOT );
        }

        private void liftTowardsRoot( int index )
        {
            int parentIndex;
            while ( index > ROOT && lessThan( parentIndex = index >> 1, index ) )
            {
                swap( index, parentIndex );
                index = parentIndex;
            }
        }

        private void pushTowardsBottom( int index )
        {
            int child;
            while ( (child = index << 1) <= size )
            {
                if ( child < size && lessThan( child, child + 1 ) )
                {
                    child += 1;
                }
                if ( !lessThan( index, child ) )
                {
                    break;
                }
                swap( index, child );
                index = child;
            }
        }

        private boolean lessThan( int indexA, int indexB )
        {
            return scores[indexA] < scores[indexB];
        }

        private void swap( int indexA, int indexB )
        {
            long entity = entities[indexA];
            float score = scores[indexA];
            entities[indexA] = entities[indexB];
            scores[indexA] = scores[indexB];
            entities[indexB] = entity;
            scores[indexB] = score;
        }
    }

    static class EntityResultsIterator implements ValuesIterator, LongFloatProcedure
    {
        private final PriorityQueue<EntityScorePriorityQueue> results;
        public long currentEntity;
        public float currentScore;

        EntityResultsIterator( PriorityQueue<EntityScorePriorityQueue> results )
        {
            this.results = results;
        }

        private void fetchNext()
        {
            EntityScorePriorityQueue pq = results.poll();
            if ( pq != null )
            {
                pq.removeMax( this );
                if ( !pq.isEmpty() )
                {
                    results.add( pq );
                }
            }
        }

        @Override
        public int remaining()
        {
            return 0; // Not used.
        }

        @Override
        public float currentScore()
        {
            return currentScore;
        }

        @Override
        public long next()
        {
            if ( hasNext() )
            {
                fetchNext();
                return currentEntity;
            }
            else
            {
                throw new NoSuchElementException();
            }
        }

        @Override
        public boolean hasNext()
        {
            return !results.isEmpty();
        }

        @Override
        public long current()
        {
            return currentEntity;
        }

        @Override
        public void value( long entity, float score )
        {
            currentEntity = entity;
            currentScore = score;
        }
    }
}
