package com.openrsc.server.model;

import com.openrsc.server.model.entity.npc.Npc;
import com.openrsc.server.model.entity.player.Player;
import com.openrsc.server.model.world.World;
import com.openrsc.server.model.world.region.Region;
import com.openrsc.server.model.world.region.RegionManager;
import com.openrsc.server.model.world.region.TileValue;
import com.openrsc.server.util.rsc.CollisionFlag;

import java.util.Deque;
import java.util.LinkedList;

/**
 *
 * @author Graham
 *
 */
public class PathValidation {

	/* Used for non-walking specific pathing:
	 * - Throwing
	 * - Magic
	 * - Ranged
	 * - Cannons
	 * - Trading
	 * - Dueling
	 */

	private static boolean DEBUG = false;

	public static boolean checkPath(Point src, Point dest) {
		final Deque<Point> path = new LinkedList<>();

		final Point curPoint = new Point(src.getX(), src.getY());

		int diffX = dest.getX() - src.getX();
		int diffY = dest.getY() - src.getY();

		int max = Math.max(Math.abs(diffX), Math.abs(diffY));
		/* Fill in the blanks between source and destination */
		for (int currentPoint = 0; currentPoint < max; currentPoint++) {

			if (diffX > 0) {
				diffX--;
			} else if (diffX < 0) {
				diffX++;
			}
			if (diffY > 0) {
				diffY--;
			} else if (diffY < 0) {
				diffY++;
			}

			path.addLast(new Point(dest.getX() - diffX, dest.getY() - diffY));
		}

		/* Loop through the path and check for blocking walls */

		Point nextPoint = null;
		while ((nextPoint = path.poll()) != null) {
			if (!checkAdjacent(curPoint, nextPoint)) return false;
			curPoint.x = nextPoint.x;
			curPoint.y = nextPoint.y;
		}
		return true;
	}

	public static boolean checkAdjacent(Point curPoint, Point nextPoint) {
		int[] coords = { curPoint.getX(), curPoint.getY() };
		int startX = curPoint.getX();
		int startY = curPoint.getY();
		int destX = nextPoint.getX();
		int destY = nextPoint.getY();
		boolean myXBlocked = false, myYBlocked = false, newXBlocked = false, newYBlocked = false;

		if (startX > destX) {
			// Check for wall on east edge of current square,
			myXBlocked = checkBlocking(startX, startY, CollisionFlag.WALL_EAST, true);
			// Or on west edge of square we are travelling toward.
			newXBlocked = checkBlocking(startX - 1, startY, CollisionFlag.WALL_WEST, false);
			coords[0] = startX - 1;
		} else if (startX < destX) {
			// Check for wall on west edge of current square,
			myXBlocked = checkBlocking(startX, startY, CollisionFlag.WALL_WEST, true);
			// Or on east edge of square we are travelling toward.
			newXBlocked = checkBlocking(startX + 1, startY, CollisionFlag.WALL_EAST, false);
			coords[0] = startX + 1;
		}

		if (startY > destY) {
			// Check for wall on north edge of current square,
			myYBlocked = checkBlocking(startX, startY, CollisionFlag.WALL_NORTH, true);
			// Or on south edge of square we are travelling toward.
			newYBlocked = checkBlocking(startX, startY - 1, CollisionFlag.WALL_SOUTH, false);
			coords[1] = startY - 1;

		} else if (startY < destY) {
			// Check for wall on south edge of current square,
			myYBlocked = checkBlocking(startX, startY, CollisionFlag.WALL_SOUTH, true);
			// Or on north edge of square we are travelling toward.
			newYBlocked = checkBlocking(startX, startY + 1, CollisionFlag.WALL_NORTH, false);
			coords[1] = startX + 1;
		}

		if (myXBlocked && myYBlocked) return false;
		if (myXBlocked && startY == destY) return false;
		if (myYBlocked && startX == destX) return false;
		if (newXBlocked && newYBlocked) return false;
		if (newXBlocked && startY == coords[1]) return false;
		if (newYBlocked && startX == coords[0]) return false;
		if (myXBlocked && newXBlocked) return false;
		if (myYBlocked && newYBlocked) return false;

		if (coords[0] > startX) {
			newXBlocked = checkBlocking(coords[0], coords[1], CollisionFlag.WALL_EAST, false);
		}
		else if (coords[0] < startX) {
			newXBlocked = checkBlocking(coords[0], coords[1], CollisionFlag.WALL_WEST, false);
		}

		if (coords[1] > startY) {
			newXBlocked = checkBlocking(coords[0], coords[1], CollisionFlag.WALL_NORTH, false);
		}
		else if (coords[1] < startY) {
			newXBlocked = checkBlocking(coords[0], coords[1], CollisionFlag.WALL_SOUTH, false);
		}

		if (newXBlocked && newYBlocked) return false;
		if (newXBlocked && startY == coords[1]) return false;
		if (myYBlocked && startX == coords[0]) return false;
		if (myXBlocked && newXBlocked) return false;
		if (myYBlocked && newYBlocked) return false;

		// Diagonal checks
		boolean diagonalBlocked = false;
		if (startX + 1 == destX && startY + 1 == destY)
			diagonalBlocked = checkBlocking(startX + 1, startY + 1,
				CollisionFlag.WALL_NORTH + CollisionFlag.WALL_EAST, false);
		else if (startX + 1 == destX && startY - 1 == destY)
			diagonalBlocked = checkBlocking(startX + 1, startY - 1,
				CollisionFlag.WALL_SOUTH + CollisionFlag.WALL_EAST, false);
		else if (startX - 1 == destX && startY + 1 == destY)
			diagonalBlocked = checkBlocking(startX - 1, startY + 1,
				CollisionFlag.WALL_NORTH + CollisionFlag.WALL_WEST, false);
		else if (startX - 1 == destX && startY - 1 == destY)
			diagonalBlocked = checkBlocking(startX - 1, startY - 1,
				CollisionFlag.WALL_SOUTH + CollisionFlag.WALL_WEST, false);

		if (diagonalBlocked)
			return false;

		return true;
	}

	private static boolean checkBlocking(int x, int y, int bit, boolean isCurrentTile) {
		TileValue t = World.getWorld().getTile(x, y);
		if(t.projectileAllowed) {
			return false;
		}

		return isBlocking(t.traversalMask, (byte) bit, isCurrentTile);
	}

	public static boolean isBlocking(int objectValue, byte bit, boolean isCurrentTile) {
		if ((objectValue & bit) != 0) { // There is a wall in the way
			return true;
		}
		if (!isCurrentTile && (objectValue & CollisionFlag.FULL_BLOCK_A) != 0) { // There is a diagonal wall here: \
			return true;
		}
		if (!isCurrentTile && (objectValue & CollisionFlag.FULL_BLOCK_B) != 0) { // There is a diagonal wall here: /
			return true;
		}
		if (!isCurrentTile && (objectValue & CollisionFlag.FULL_BLOCK_C) != 0) { // This tile is unwalkable
			return true;
		}
		if (!isCurrentTile && (objectValue & CollisionFlag.OBJECT) != 0) { // Object?
			return true;
		}
		return false;
	}

	public static boolean checkDiagonalPassThroughCollisions(Point curPoint, Point nextPoint) {

		int x = curPoint.getX();
		int y = curPoint.getY();
		int x_next = nextPoint.getX();
		int y_next = nextPoint.getY();

		// Moving northeast
		if (x_next == x - 1 && y_next == y - 1) {
			if (DEBUG) System.out.println("Northeast");
			return checkNortheast(curPoint);
		}

		// Moving northwest
		else if (x_next == x + 1 && y_next == y - 1) {
			if (DEBUG) System.out.println("Northwest");
			return checkNorthwest(curPoint);
		}

		// Moving southeast
		else if (x_next == x - 1 && y_next == y + 1) {
			if (DEBUG) System.out.println("Southeast");
			return checkSoutheast(curPoint);
		}

		// Moving southwest
		else if (x_next == x + 1 && y_next == y + 1) {
			if (DEBUG) System.out.println("Southwest");
			return checkSouthwest(curPoint);
		}

		return false; // No collisions
	}

	private static boolean checkNortheast(Point curPoint) {

		int x = curPoint.getX();
		int y = curPoint.getY();

		// Object east
		// |   or   |
		//  \        X
		int mask = World.getWorld().getTile(x - 1, y).traversalMask;
		boolean blocking = (mask & (CollisionFlag.FULL_BLOCK_A + CollisionFlag.FULL_BLOCK_C)) != 0;
		if (blocking) {

			// Wall on north tile, east side
			mask = World.getWorld().getTile(x, y - 1).traversalMask;
			blocking = (mask & CollisionFlag.WALL_EAST) != 0;
			if (blocking) {
				if (DEBUG) System.out.println("East Object - East Wall");
				return true;
			}

			// Wall on northeast tile, west side
			mask = World.getWorld().getTile(x - 1, y - 1).traversalMask;
			blocking = (mask & CollisionFlag.WALL_WEST) != 0;
			if (blocking) {
				if (DEBUG) System.out.println("East Object - West Wall");
				return true;
			}
		}

		// Object north
		// \__  or  X__
		mask = World.getWorld().getTile(x, y - 1).traversalMask;
		blocking = (mask & (CollisionFlag.FULL_BLOCK_A + CollisionFlag.FULL_BLOCK_C)) != 0;
		if (blocking) {

			// Wall on east tile, north side
			mask = World.getWorld().getTile(x - 1, y).traversalMask;
			blocking = (mask & CollisionFlag.WALL_NORTH) != 0;
			if (blocking) {
				if (DEBUG) System.out.println("North Object - North Wall");
				return true;
			}

			// Wall on northeast tile, south side
			mask = World.getWorld().getTile(x - 1, y - 1).traversalMask;
			blocking = (mask & CollisionFlag.WALL_SOUTH) != 0;
			if (blocking) {
				if (DEBUG) System.out.println("North Object - South Wall");
				return true;
			}

		}

		return false;

	}

	private static boolean checkNorthwest(Point curPoint) {

		int x = curPoint.getX();
		int y = curPoint.getY();

		// Object west
		//   |  or  |
		//  /      X
		int mask = World.getWorld().getTile(x + 1, y).traversalMask;
		boolean blocking = (mask & (CollisionFlag.FULL_BLOCK_B + CollisionFlag.FULL_BLOCK_C)) != 0;
		if (blocking) {

			// Wall on north tile, west side
			mask = World.getWorld().getTile(x, y - 1).traversalMask;
			blocking = (mask & CollisionFlag.WALL_WEST) != 0;
			if (blocking) {
				if (DEBUG) System.out.println("West Object - West Wall");
				return true;
			}

			// Wall on northwest tile, east side
			mask = World.getWorld().getTile(x + 1, y + 1).traversalMask;
			blocking = (mask & CollisionFlag.WALL_EAST) != 0;
			if (blocking) {
				if (DEBUG) System.out.println("West Object - East Wall");
				return true;
			}
		}

		// Object north
		// __/  or  __X
		mask = World.getWorld().getTile(x, y - 1).traversalMask;
		blocking = (mask & (CollisionFlag.FULL_BLOCK_B + CollisionFlag.FULL_BLOCK_C)) != 0;
		if (blocking) {

			// Wall on west tile, north side
			mask = World.getWorld().getTile(x + 1, y).traversalMask;
			blocking = (mask & CollisionFlag.WALL_NORTH) != 0;
			if (blocking) {
				if (DEBUG) System.out.println("North Object - North Wall");
				return true;
			}

			// Wall on northwest tile, south side
			mask = World.getWorld().getTile(x + 1, y - 1).traversalMask;
			blocking = (mask & CollisionFlag.WALL_SOUTH) != 0;
			if (blocking) {
				if (DEBUG) System.out.println("North Object - South Wall");
				return true;
			}

		}

		return false;
	}

	private static boolean checkSoutheast(Point curPoint) {

		int x = curPoint.getX();
		int y = curPoint.getY();

		// Object east
		//   /  or   X
		//  |       |
		int mask = World.getWorld().getTile(x - 1, y).traversalMask;
		boolean blocking = (mask & (CollisionFlag.FULL_BLOCK_B + CollisionFlag.FULL_BLOCK_C)) != 0;
		if (blocking) {

			// Wall on south tile, east side
			mask = World.getWorld().getTile(x, y + 1).traversalMask;
			blocking = (mask & CollisionFlag.WALL_EAST) != 0;
			if (blocking) {
				if (DEBUG) System.out.println("East Object - East Wall");
				return true;
			}

			// Wall on southeast tile, west side
			mask = World.getWorld().getTile(x - 1, y + 1).traversalMask;
			blocking = (mask & CollisionFlag.WALL_WEST) != 0;
			if (blocking) {
				if (DEBUG) System.out.println("East Object - West Wall");
				return true;
			}
		}

		// Object south
		//  __       __
		// /    or  X
		mask = World.getWorld().getTile(x, y + 1).traversalMask;
		blocking = (mask & (CollisionFlag.FULL_BLOCK_B + CollisionFlag.FULL_BLOCK_C)) != 0;
		if (blocking) {

			// Wall on east tile, south side
			mask = World.getWorld().getTile(x - 1, y).traversalMask;
			blocking = (mask & CollisionFlag.WALL_SOUTH) != 0;
			if (blocking) {
				if (DEBUG) System.out.println("South Object - South Wall");
				return true;
			}

			// Wall on southeast tile, north side
			mask = World.getWorld().getTile(x - 1, y + 1).traversalMask;
			blocking = (mask & CollisionFlag.WALL_NORTH) != 0;
			if (blocking) {
				if (DEBUG) System.out.println("South Object - North Wall");
				return true;
			}

		}

		return false;

	}

	private static boolean checkSouthwest(Point curPoint) {

		int x = curPoint.getX();
		int y = curPoint.getY();

		// Object west
		//  \  or  X
		//   |      |
		int mask = World.getWorld().getTile(x + 1, y).traversalMask;
		boolean blocking = (mask & (CollisionFlag.FULL_BLOCK_A + CollisionFlag.FULL_BLOCK_C)) != 0;
		if (blocking) {

			// Wall on south tile, west side
			mask = World.getWorld().getTile(x, y + 1).traversalMask;
			blocking = (mask & CollisionFlag.WALL_WEST) != 0;
			if (blocking) {
				if (DEBUG) System.out.println("West Object - West Wall");
				return true;
			}

			// Wall on southwest tile, east side
			mask = World.getWorld().getTile(x + 1, y + 1).traversalMask;
			blocking = (mask & CollisionFlag.WALL_EAST) != 0;
			if (blocking) {
				if (DEBUG) System.out.println("West Object - East Wall");
				return true;
			}
		}

		// Object south
		// __       __
		//   \  or    X
		mask = World.getWorld().getTile(x, y + 1).traversalMask;
		blocking = (mask & (CollisionFlag.FULL_BLOCK_A + CollisionFlag.FULL_BLOCK_C)) != 0;
		if (blocking) {

			// Wall on west tile, south side
			mask = World.getWorld().getTile(x + 1, y).traversalMask;
			blocking = (mask & CollisionFlag.WALL_SOUTH) != 0;
			if (blocking) {
				if (DEBUG) System.out.println("South Object - South Wall");
				return true;
			}

			// Wall on southwest tile, north side
			mask = World.getWorld().getTile(x + 1, y + 1).traversalMask;
			blocking = (mask & CollisionFlag.WALL_NORTH) != 0;
			if (blocking) {
				if (DEBUG) System.out.println("South Object - North Wall");
				return true;
			}

		}

		return false;

	}

}
