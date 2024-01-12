package waffle;

import battlecode.common.*;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

public strictfp class RobotPlayer {

    static final Random rng = new Random(742236);

    static final int ADJACENT_DISTANCE_SQUARED = 2;

    static int roundNumAtStartOfIteration = 0;

    /** Array containing all the possible movement directions. */
    static final Direction[] MOVEMENT_DIRECTIONS = {
        Direction.NORTH,
        Direction.NORTHEAST,
        Direction.EAST,
        Direction.SOUTHEAST,
        Direction.SOUTH,
        Direction.SOUTHWEST,
        Direction.WEST,
        Direction.NORTHWEST,
    };
    @SuppressWarnings("unused")
    public static void run(RobotController rc) throws GameActionException {

        // You can also use indicators to save debug notes in replays.
        rc.setIndicatorString("Hello world!");

        while (true) {
            // Try/catch blocks stop unhandled exceptions, which cause your robot to explode.
            try {
                roundNumAtStartOfIteration = rc.getRoundNum();

                // Make sure you spawn your robot in before you attempt to take any actions!
                // Robots not spawned in do not have vision of any tiles and cannot perform any actions.
                if (!rc.isSpawned()){
                    MapLocation[] spawnLocs = rc.getAllySpawnLocations();
                    MapLocation mySpawnLoc = spawnLocs[rc.getID() % spawnLocs.length];
                    if (rc.canSpawn(mySpawnLoc)) {
                        rc.spawn(mySpawnLoc);
                    }
                } else {
                    updateRobotArrays(rc);
                    manageEnemyFlagBroadcastData(rc);

                    pickupEnemyFlags(rc);
                    
                    // If we are holding an enemy flag, singularly focus on moving towards
                    // an ally spawn zone to capture it! We use the check roundNum >= SETUP_ROUNDS
                    // to make sure setup phase has ended.
                    if (rc.hasFlag() && rc.getRoundNum() >= GameConstants.SETUP_ROUNDS){
                        hybridMove(rc, getNearestSpawnLoc(rc));
                    }
                    
                    attack(rc);

                    moveAssumingDontHaveFlag(rc);

                    buildCombatTraps(rc);
                    
                    attack(rc);

                    heal(rc);

                    doGlobalUpgrades(rc);
                }

                if(rc.getRoundNum() != roundNumAtStartOfIteration) {
                    System.out.println("ROUND NUM INCREASED FROM " + roundNumAtStartOfIteration + " TO " + rc.getRoundNum());
                }

            } catch (GameActionException e) {
                // Oh no! It looks like we did something illegal in the Battlecode world. You should
                // handle GameActionExceptions judiciously, in case unexpected events occur in the game
                // world. Remember, uncaught exceptions cause your robot to explode!
                System.out.println("GameActionException");
                e.printStackTrace();

            } catch (Exception e) {
                // Oh no! It looks like our code tried to do something bad. This isn't a
                // GameActionException, so it's more likely to be a bug in our code.
                System.out.println("Exception");
                e.printStackTrace();

            } finally {
                // Signify we've done everything we want to do, thereby ending our turn.
                // This will make our code wait until the next turn, and then perform this loop again.
                Clock.yield();
            }
            // End of loop: go back to the top. Clock.yield() has ended, so it's time for another turn!
        }

        // Your code should never reach here (unless it's intentional)! Self-destruction imminent...
    }

    static int roundLastAttacked = 0;
    /**
     * PRECONDITION: canAttack must have returned true for this locToAttack
     */
    static void attackAndUpdateMyVariables(RobotController rc, MapLocation locToAttack) throws GameActionException {
        rc.attack(locToAttack);
        roundLastAttacked = rc.getRoundNum();
    }
    static void attack(RobotController rc) throws GameActionException {
        double minScore = 0;
        RobotInfo bestRbt = null;
        for(int enemyRbtIdx = 0; enemyRbtIdx < nearbyEnemyRobotsLength; enemyRbtIdx++) {
            RobotInfo enemyRbt = nearbyEnemyRobots[enemyRbtIdx];
            if(
                rc.canAttack(enemyRbt.location)
                && (
                    bestRbt == null
                    || getAttackTargetScoreToMinimize(rc, enemyRbt) < minScore
                )
            ) {
                bestRbt = enemyRbt;
                minScore = getAttackTargetScoreToMinimize(rc, enemyRbt);
            }
        }
        if(bestRbt != null) {
            attackAndUpdateMyVariables(rc, bestRbt.location);
        }
    }
    static double getAttackTargetScoreToMinimize(RobotController rc, RobotInfo enemyRbt) {
        return enemyRbt.health + rc.getLocation().distanceSquaredTo(enemyRbt.location);
    }

    static void heal(RobotController rc) throws GameActionException {
        if(rc.getRoundNum() - roundLastAttacked >= 2) {
            int minHealth = 0;
            RobotInfo bestRbt = null;
            for(int friendlyRbtIdx = 0; friendlyRbtIdx < nearbyFriendlyRobotsLength; friendlyRbtIdx++) {
                RobotInfo friendlyRbt = nearbyFriendlyRobots[friendlyRbtIdx];
                if(
                    rc.canHeal(friendlyRbt.location)
                    && (bestRbt == null || friendlyRbt.health < minHealth)
                ) {
                    bestRbt = friendlyRbt;
                    minHealth = friendlyRbt.health;
                }
            }
            if(bestRbt != null) {
                rc.heal(bestRbt.location);
            }
        }
    }

    static void buildCombatTraps(RobotController rc) throws GameActionException {
        final TrapType trapTypeToBuild = nearbyFriendlyRobotsLength >= 5 ? TrapType.STUN : TrapType.EXPLOSIVE;
        if(nearbyEnemyRobotsLength >= 5) {
            double bestScore = 0;
            Direction bestDir = null;
            for(Direction d : MOVEMENT_DIRECTIONS) {
                MapLocation candidateLocation = rc.adjacentLocation(d);
                if(rc.canBuild(trapTypeToBuild, candidateLocation)
                    && rc.senseMapInfo(candidateLocation).isPassable()
                ) {
                    double score = 0;
                    for(int k = 0; k < nearbyEnemyRobotsLength; k++) {
                        score += (((double)1) / candidateLocation.distanceSquaredTo(nearbyEnemyRobots[k].location));
                    }
                    if(score >= ((double)1000) / rc.getCrumbs() && score > bestScore) {
                        boolean isAlreadyTrapNearby = false;
                        for(MapInfo mi : rc.senseNearbyMapInfos(candidateLocation, 3*3)) {
                            if(trapTypeToBuild.equals(mi.getTrapType())) {
                                isAlreadyTrapNearby = true;
                                break;
                            }
                        }
                        if(!isAlreadyTrapNearby) {
                            bestDir = d;
                            bestScore = score;
                        }
                    }
                }
            }
            if(bestDir != null) {
                rc.build(trapTypeToBuild, rc.adjacentLocation(bestDir));
            }
        }
    }

    final static GlobalUpgrade [] MY_ORDER = {
        GlobalUpgrade.ACTION,
        GlobalUpgrade.HEALING,
        GlobalUpgrade.CAPTURING
    };
    static void doGlobalUpgrades(RobotController rc) throws GameActionException {
        for(int k = 0; k < MY_ORDER.length; k++) {
            if(rc.canBuyGlobal(MY_ORDER[k])) {
                rc.buyGlobal(MY_ORDER[k]);
            }
        }
    }



    static RobotInfo [] nearbyFriendlyRobots = new RobotInfo[4 * GameConstants.VISION_RADIUS_SQUARED];
    static int nearbyFriendlyRobotsLength = 0;
    static RobotInfo [] nearbyEnemyRobots = new RobotInfo[4 * GameConstants.VISION_RADIUS_SQUARED];
    static int nearbyEnemyRobotsLength = 0;
    static MapLocation locLastSawEnemy = null;
    static int roundLastSawEnemy = -12345;
    static MapLocation locLastSawFriend = null;
    static int roundLastSawFriend = -12345;
    static FlagInfo[] sensedFlags = new FlagInfo[0];
    static void updateRobotArrays(RobotController rc) throws GameActionException {
        nearbyFriendlyRobotsLength = 0;
        nearbyEnemyRobotsLength = 0;
        int totalEnemyRobotX = 0; int totalEnemyRobotY = 0;
        int totalFriendlyRobotX = 0; int totalFriendlyRobotY = 0;
        for(RobotInfo robotInfo : rc.senseNearbyRobots(-1)) {
            if(robotInfo.getTeam().equals(rc.getTeam())) {
                nearbyFriendlyRobots[nearbyFriendlyRobotsLength] = robotInfo;
                nearbyFriendlyRobotsLength++;
                totalFriendlyRobotX += robotInfo.location.x; totalFriendlyRobotY += robotInfo.location.y;
            } else {
                nearbyEnemyRobots[nearbyEnemyRobotsLength] = robotInfo;
                nearbyEnemyRobotsLength++;
                totalEnemyRobotX += robotInfo.location.x; totalEnemyRobotY += robotInfo.location.y;
            }
        }
        if(nearbyEnemyRobotsLength >= 1) {
            locLastSawEnemy = new MapLocation(
                totalEnemyRobotX / nearbyEnemyRobotsLength,
                totalEnemyRobotY / nearbyEnemyRobotsLength
            );
            roundLastSawEnemy = rc.getRoundNum();
        }
        if(nearbyFriendlyRobotsLength >= 1) {
            locLastSawFriend = new MapLocation(
                totalFriendlyRobotX / nearbyFriendlyRobotsLength,
                totalFriendlyRobotY / nearbyFriendlyRobotsLength
            );
            roundLastSawFriend = rc.getRoundNum();
        }
        sensedFlags = rc.senseNearbyFlags(-1);
    }


    static void pickupEnemyFlags(RobotController rc) throws GameActionException {
        for(FlagInfo fi : rc.senseNearbyFlags(-1, rc.getTeam().opponent())) {
            if(rc.canPickupFlag(fi.getLocation())) {
                rc.pickupFlag(fi.getLocation());
            }
        }
    }


    static double evaluateLocationForCombat(RobotController rc, MapLocation locToEvaluate) {
        double value = 0;

        double numerator = ((double)rc.getHealth() / GameConstants.DEFAULT_HEALTH)
            - 0.7;
        numerator += 0.5 * (rc.isActionReady() ? 1 : -1);
        value += numerator / (1 + locToEvaluate.distanceSquaredTo(locLastSawEnemy));

        return value;
    }
    static void moveAssumingDontHaveFlag(RobotController rc) throws GameActionException {
        if(rc.getRoundNum() >= GameConstants.SETUP_ROUNDS - 20
            && rc.getRoundNum() - roundLastSawEnemy < 5
        ) {
            double bestScore = 0;
            Direction bestDir = null;
            for(Direction d : MOVEMENT_DIRECTIONS) {
                if(rc.canMove(d)) {
                    final double score = evaluateLocationForCombat(rc, rc.adjacentLocation(d));
                    if(bestDir == null || score > bestScore) {
                        bestDir = d;
                        bestScore = score;
                    }
                }
            }
            if(bestDir != null) {
                rc.move(bestDir);
            }
        } else {
            exploreMove(rc);
        }
    }


    static MapLocation exploreTarget = null;
    static MapLocation lastTurnExploreMoveEndLoc = null;
    static boolean exploreMove(RobotController rc) throws GameActionException {
        return exploreMove(rc, defaultCanMove);
    }
    static boolean exploreMove(RobotController rc, CanMove canMove) throws GameActionException {
        boolean didMove = false;
        if(lastTurnExploreMoveEndLoc != null
            && !rc.getLocation().equals(lastTurnExploreMoveEndLoc)
        ) {
            exploreTarget = null;
        }
        while(rc.isMovementReady()) {
            int tryLimiter = 10;
            while(tryLimiter >= 0
                && (exploreTarget == null
                    || rc.getLocation().equals(exploreTarget)
                    || !canMove.test(rc, rc.getLocation().directionTo(exploreTarget))
                )
            ) {
                final int mw = rc.getMapWidth();
                final int mh = rc.getMapHeight();
                exploreTarget = new MapLocation(
                    rng.nextInt(2 * mw) - (mw / 2),
                    rng.nextInt(2 * mh) - (mh / 2)
                );
                tryLimiter--;
            }
            final Direction dir = rc.getLocation().directionTo(exploreTarget);
            if(canMove.test(rc, dir)) {
                rc.move(dir);
                didMove = true;
                rc.setIndicatorString("EXPLORE MOVE " + didMove + " " + dir.toString());
            } else {
                break;
            }
        }
        lastTurnExploreMoveEndLoc = rc.getLocation();
        return didMove;
    }


    static MapLocation getNearestSpawnLoc(RobotController rc) throws GameActionException {
        MapLocation[] spawnLocs = rc.getAllySpawnLocations();
        MapLocation bestSpawnLoc = null;
        for(int k = 0; k < spawnLocs.length; k++) {
            if(
                bestSpawnLoc == null
                || (
                    rc.getLocation().distanceSquaredTo(spawnLocs[k])
                    < rc.getLocation().distanceSquaredTo(bestSpawnLoc)
                )
            ) {
                bestSpawnLoc = spawnLocs[k];
            }
        }
        return bestSpawnLoc;
    }

    static int broadcastLocationCount = 0;
    static int [][] broadcastLocationTotals = new int[GameConstants.NUMBER_FLAGS][2];
    static void manageEnemyFlagBroadcastData(RobotController rc) {
        if(rc.getRoundNum() % GameConstants.FLAG_BROADCAST_UPDATE_INTERVAL == 2) {
            MapLocation [] data = rc.senseBroadcastFlagLocations(); // This method returns an empty array sometimes and I don't know why
            if(data.length == broadcastLocationTotals.length) {
                for(int k = 0; k < data.length; k++) {
                    broadcastLocationTotals[k][0] += data[k].x;
                    broadcastLocationTotals[k][1] += data[k].y;
                }
                broadcastLocationCount++;
                rc.setIndicatorString("Updated broadcast data");
            }
        }
    }
    static MapLocation[] getApproximateEnemyFlagLocationsMayBeEmpty(RobotController rc) {
        if(broadcastLocationCount >= 1) {
            MapLocation [] result = new MapLocation[broadcastLocationTotals.length];
            for(int k = 0; k < broadcastLocationTotals.length; k++) {
                result[k] = new MapLocation(
                    broadcastLocationTotals[k][0] / broadcastLocationCount,
                    broadcastLocationTotals[k][1] / broadcastLocationCount
                );
            }
            return result;
        } else {
            return new MapLocation[0];
        }
    }
    static MapLocation getNearestApproximateEnemyFlagLocationMayBeNull(RobotController rc) {
        int minDistSqd = 0; MapLocation bestLoc = null;
        for(MapLocation ml : getApproximateEnemyFlagLocationsMayBeEmpty(rc)) {
            final int dist = rc.getLocation().distanceSquaredTo(ml);
            if(bestLoc == null || dist < minDistSqd) {
                bestLoc = ml; minDistSqd = dist;
            }
        }
        return bestLoc;
    }


    static interface CanMove {
        boolean test(RobotController rc, Direction d) throws GameActionException;
    }
    static CanMove defaultCanMove = (rc, d) -> rc.canMove(d);

    static enum HybridStatus {FUZZY, BUG;}
    static HybridStatus hybridStatus = HybridStatus.FUZZY;
    static MapLocation bugObstacle = null;
    static Set<MapLocation> bugMemory = null;
    static boolean isBugRotatingLeft = false;
    static int bugStartDistFromDest = -1;
    static MapLocation hybridMoveLastCallEndLoc = null;
    static MapLocation hybridMoveLastCallDest = null;
    static void startBug(RobotController rc, MapLocation dest) {
        hybridStatus = HybridStatus.BUG;
        bugObstacle = rc.adjacentLocation(rc.getLocation().directionTo(dest));
        bugMemory = new HashSet<MapLocation>();
        isBugRotatingLeft = rng.nextBoolean();
        bugStartDistFromDest = rc.getLocation().distanceSquaredTo(dest);
    }
    static void endBugStartFuzzy() {
        hybridStatus = HybridStatus.FUZZY;
        bugObstacle = null;
        bugMemory = null;
        bugStartDistFromDest = -1;
    }
    static int dot(Direction d1, Direction d2) {
        return (d1.dx*d2.dx) + (d1.dy*d2.dy);
    }
    static boolean bugCanMove(RobotController rc, Direction d, CanMove canMove) throws GameActionException {
        return canMove.test(rc, d);
    }
    static Direction getRotated(Direction d, boolean left) {
        return left ? d.rotateLeft() : d.rotateRight();
    }
    static void hybridMove(RobotController rc, MapLocation dest) throws GameActionException {
        hybridMove(rc, dest, defaultCanMove);
    }
    static void hybridMove(RobotController rc, MapLocation dest, CanMove canMove) throws GameActionException {
        if(hybridMoveLastCallEndLoc == null
            || !hybridMoveLastCallEndLoc.equals(rc.getLocation())
            || hybridMoveLastCallDest == null
            || !hybridMoveLastCallDest.equals(dest)
        ) {
            endBugStartFuzzy();
        }
        boolean isStuck = false;
        while(!isStuck
            && rc.isMovementReady()
            && !rc.getLocation().equals(dest)
        ) {
            isStuck = true;
            if(HybridStatus.FUZZY.equals(hybridStatus)) {
                int bestDist = 12345;
                Direction bestDir = null;
                for(Direction d : MOVEMENT_DIRECTIONS) {
                    final MapLocation neighbor = rc.adjacentLocation(d);
                    if(canMove.test(rc, d)
                        && (
                            bestDir == null
                            || bestDist > neighbor.distanceSquaredTo(dest)
                        )
                    ) {
                        bestDir = d;
                        bestDist = neighbor.distanceSquaredTo(dest);
                    }
                }
                if(bestDir != null) {
                    isStuck = false;
                    if(bestDist < rc.getLocation().distanceSquaredTo(dest)) {
                        rc.move(bestDir);
                    } else {
                        startBug(rc, dest);
                    }
                } // else we're stuck
            } else if(HybridStatus.BUG.equals(hybridStatus)) {
                if(bugMemory.contains(rc.getLocation())) {
                    endBugStartFuzzy();
                    isStuck = false;
                } else if(rc.getLocation().distanceSquaredTo(dest) <= ADJACENT_DISTANCE_SQUARED
                    && !bugCanMove(rc, rc.getLocation().directionTo(dest), canMove)
                ) {
                    // If we are adjacent to the dest but cannot move to the dest,
                    //   then the dest is obstructed, so don't bug away from the dest.
                    endBugStartFuzzy();
                    // Let isStuck be true.
                } else {
                    Direction bugDirection = rc.getLocation().directionTo(bugObstacle);
                    if(bugCanMove(rc, bugDirection, canMove)) {
                        // The obstacle has moved out of the way
                        endBugStartFuzzy();
                        isStuck = false;
                    } else {
                        int rotateCount = 0;
                        while(!bugCanMove(rc, bugDirection, canMove) && rotateCount < 8) {
                            bugObstacle = rc.adjacentLocation(bugDirection);
                            bugDirection = getRotated(bugDirection, isBugRotatingLeft);
                            rotateCount++;
                        }
                        if(!bugCanMove(rc, bugDirection, canMove)) {
                            endBugStartFuzzy();
                            // We're stuck; we cannot move.
                            // Let isStuck be true.
                        } else {
                            bugMemory.add(rc.getLocation());
                            rc.move(bugDirection);
                            isStuck = false;
                            if(rc.getLocation().distanceSquaredTo(dest) < bugStartDistFromDest) {
                                endBugStartFuzzy();
                            }
                        }
                    }
                }
            } // end if hybridStatus equals BUG
        } // end while
// rc.setIndicatorString("hybridMove round" + rc.getRoundNum() + " dest " + dest.toString() + " ending in status " + hybridStatus);

        hybridMoveLastCallEndLoc = rc.getLocation();
        hybridMoveLastCallDest = dest;
    }

}
