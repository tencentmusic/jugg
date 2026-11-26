package com.android.tools.deployer.common;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

/** Quail deployer compatibility type rebuilt for the standalone Java 11 runtime. */
public class ApkMap {
   private List<Area> cleanAreas = new ArrayList();
   private final long size;

   public ApkMap(long size) {
      this.size = size;
   }

   public void markClean(Area cleanArea) {
      this.cleanAreas.add(cleanArea);
   }

   public List<Area> getDirtyAreas() {
      this.cleanAreas.add(new Area(-1L, -1L));
      this.cleanAreas.add(new Area(this.size, this.size));
      this.cleanAreas.sort((o1, o2) -> Long.compare(o1.start, o2.start));
      LinkedList<Area> dirtyAreas = new LinkedList();
      Iterator<Area> iterator = this.cleanAreas.iterator();

      Area current;
      for(Area previous = (Area)iterator.next(); iterator.hasNext(); previous = current) {
         current = (Area)iterator.next();
         Area dirtyArea = new Area(previous.end + 1L, current.start - 1L);
         if (dirtyArea.size() > 0L) {
            dirtyAreas.add(dirtyArea);
         }
      }

      return dirtyAreas;
   }

   public static class Area {
      final long start;
      final long end;

      public Area(long start, long end) {
         this.start = start;
         this.end = end;
      }

      long size() {
         return this.end - this.start + 1L;
      }
   }
}
