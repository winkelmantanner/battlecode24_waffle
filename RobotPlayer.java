package waffle;

import battlecode.common.*;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;

public strictfp class RobotPlayer {

    static Random rng;

    static final int ADJACENT_DISTANCE_SQUARED = 2;

    static int roundNumAtStartOfIteration = 0;

    static MapLocation lastSpawLocation = null;

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
        rng = new Random(rc.getID());

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
                    MapLocation locToTry = spawnLocs[rng.nextInt(spawnLocs.length)];
                    if (rc.canSpawn(locToTry)) {
                        rc.spawn(locToTry);
                        lastSpawLocation = locToTry;
                    }
                } else {
                    updateData(rc);
                    manageEnemyFlagBroadcastData(rc);
                    callForHelpIfEnemiesApproachFlag(rc);

                    pickupEnemyFlags(rc);
                    
                    attack(rc);

                    move(rc);

                    buildDefensiveTraps(rc);

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

    static void buildDefensiveTraps(RobotController rc) throws GameActionException {
        for(FlagInfo fi : sensedFlags) {
            if(rc.getLocation().equals(fi.getLocation())
                && rc.getTeam().equals(fi.getTeam())
            ) {
                for(MapInfo ml : rc.senseNearbyMapInfos(2)) {
                    TrapType trapTypeToBuild;
                    if(1 == rc.getLocation().distanceSquaredTo(ml.getMapLocation())) {
                        trapTypeToBuild = TrapType.STUN;
                    } else {
                        trapTypeToBuild = TrapType.EXPLOSIVE;
                    }
                    if(rc.canBuild(trapTypeToBuild, ml.getMapLocation())) {
                        rc.build(trapTypeToBuild, ml.getMapLocation());
                    }
                }
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
    static RobotInfo nearestEnemyRobot = null;
    static MapLocation locLastSawEnemy = null;
    static int roundLastSawEnemy = -12345;
    static MapLocation locLastSawFriend = null;
    static int roundLastSawFriend = -12345;
    static FlagInfo[] sensedFlags = new FlagInfo[0];
    static FlagInfo nearestSensedEnemyFlag = null;
    static int callForAssistanceRoundNum = 0;
    static MapLocation callForAssitanceLoc = null;
    static void updateData(RobotController rc) throws GameActionException {
        nearbyFriendlyRobotsLength = 0;
        nearbyEnemyRobotsLength = 0;
        nearestEnemyRobot = null; int minDistSqdToEnemy = 12345;
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
                final int dist = rc.getLocation().distanceSquaredTo(robotInfo.getLocation());
                if(dist < minDistSqdToEnemy) {
                    nearestEnemyRobot = robotInfo; minDistSqdToEnemy = dist;
                }
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
        int minDistSqd = 12345;
        nearestSensedEnemyFlag = null;
        for(FlagInfo fi : sensedFlags) {
            if(rc.getTeam().opponent().equals(fi.getTeam())) {
                final int dist = rc.getLocation().distanceSquaredTo(fi.getLocation());
                if(dist < minDistSqd) {
                    minDistSqd = dist;
                    nearestSensedEnemyFlag = fi;
                }
            }
        }

        callForAssistanceRoundNum = rc.readSharedArray(CALL_FOR_ASSISTANCE_ROUND_INDEX);
        if(callForAssistanceRoundNum != 0) {
            callForAssitanceLoc = new MapLocation(
                rc.readSharedArray(CALL_FOR_ASSISTANCE_X_INDEX),
                rc.readSharedArray(CALL_FOR_ASSISTANCE_Y_INDEX)
            );
        }
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
        value += numerator / (1 + locToEvaluate.distanceSquaredTo(
            nearestEnemyRobot != null ? nearestEnemyRobot.location : locLastSawEnemy
        ));

        return value;
    }
    static void move(RobotController rc) throws GameActionException {
        // If we are holding an enemy flag, singularly focus on moving towards
        // an ally spawn zone to capture it! We use the check roundNum >= SETUP_ROUNDS
        // to make sure setup phase has ended.
        if (rc.hasFlag() && rc.getRoundNum() >= GameConstants.SETUP_ROUNDS){
            hybridMove(rc, getNearestSpawnLoc(rc));
            if(nearbyEnemyRobotsLength >= 2 || rc.getHealth() < 0.5 * GameConstants.DEFAULT_HEALTH) {
                callForAssistance(rc, rc.getLocation());
            }
        }

        boolean isOnFriendlyFlag = false;
        for(FlagInfo fi : sensedFlags) {
            if(rc.getTeam().equals(fi.getTeam())) {
                if(rc.getLocation().equals(fi.getLocation())) {
                    isOnFriendlyFlag = true;
                } else if(rc.canSenseLocation(fi.getLocation())
                    && null == rc.senseRobotAtLocation(fi.getLocation())
                ) {
                    hybridMove(rc, fi.getLocation());
                }
            }
        }

        if(!isOnFriendlyFlag) {
            if(rc.getRoundNum() < GameConstants.SETUP_ROUNDS - 20) {
                exploreMove(rc);
            }

            // THIS MADE IT WORSE
            // if(rc.getHealth() <= 0.5 * GameConstants.DEFAULT_HEALTH) {
            //     double maxValue = 0;
            //     Direction bestDir = null;
            //     for(Direction d : MOVEMENT_DIRECTIONS) {
            //         if(rc.canMove(d)) {
            //             final MapLocation ml = rc.adjacentLocation(d);
            //             double value = 0;
            //             for(int k = 0; k < nearbyFriendlyRobotsLength; k++) {
            //                 value += (double)1 / (ml.distanceSquaredTo(nearbyFriendlyRobots[k].location));
            //             }
            //             for(int k = 0; k < nearbyEnemyRobotsLength; k++) {
            //                 value -= (double)1 / (ml.distanceSquaredTo(nearbyEnemyRobots[k].location));
            //             }
            //             if(bestDir == null || value > maxValue) {
            //                 bestDir = d; maxValue = value;
            //             }
            //         }
            //     }
            //     if(bestDir != null) {
            //         rc.move(bestDir);
            //     }
            // }

            if(nearestSensedEnemyFlag != null
                && !nearestSensedEnemyFlag.isPickedUp()
            ) {
                hybridMove(rc, nearestSensedEnemyFlag.getLocation());
            }

            if(nearestEnemyRobot != null
                && rc.getLocation().distanceSquaredTo(nearestEnemyRobot.location)
                    <= 6 + GameConstants.ATTACK_RADIUS_SQUARED
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
                // I tried making it go for whichever is nearer of target and callForAssitanceLoc, and it won 8, lost 13 against the previous version.  Note that I ignore rounds were the victory condition is level sum, or anything other than flags.
                MapLocation target = getNearestNonEliminatedEnemyFlagLocationMayBeNull(rc);
                if(target != null
                    && nearbyFriendlyRobotsLength >= 1
                ) {
                    hybridMove(rc, target);
                } else {
                    if(callForAssitanceLoc != null
                        && rc.getRoundNum() - callForAssistanceRoundNum < 30
                        && rc.getLocation().distanceSquaredTo(callForAssitanceLoc) < 20*20
                    ) {
                        hybridMove(rc, callForAssitanceLoc);
                    }

                    hybridMove(rc, lastSpawLocation);
                }
            }
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
            } else {
                break;
            }
        }
        lastTurnExploreMoveEndLoc = rc.getLocation();
        return didMove;
    }

    static final int CALL_FOR_ASSISTANCE_X_INDEX = 0;
    static final int CALL_FOR_ASSISTANCE_Y_INDEX = 1;
    static final int CALL_FOR_ASSISTANCE_ROUND_INDEX = 2;
    static void callForAssistance(RobotController rc, MapLocation locToSend) throws GameActionException {
        rc.writeSharedArray(CALL_FOR_ASSISTANCE_X_INDEX, locToSend.x);
        rc.writeSharedArray(CALL_FOR_ASSISTANCE_Y_INDEX, locToSend.y);
        rc.writeSharedArray(CALL_FOR_ASSISTANCE_ROUND_INDEX, rc.getRoundNum());
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

    // static int broadcastLocationCount = 0;
    // static int [][] broadcastLocationTotals = new int[GameConstants.NUMBER_FLAGS][2];
    static class MyBroadcastLocationData {boolean isEliminated = false;}
    static Map<MapLocation, MyBroadcastLocationData> myBroadcastMap = new HashMap<>();
    static void manageEnemyFlagBroadcastData(RobotController rc) {
        // if((rc.getRoundNum() - 2) / GameConstants.FLAG_BROADCAST_UPDATE_INTERVAL >= broadcastLocationCount) {
        //     MapLocation [] data = rc.senseBroadcastFlagLocations(); // This method returns an empty array sometimes and I don't know why
        //     if(data.length == broadcastLocationTotals.length) {
        //         for(int k = 0; k < data.length; k++) {
        //             broadcastLocationTotals[k][0] += data[k].x;
        //             broadcastLocationTotals[k][1] += data[k].y;
        //         }
        //         broadcastLocationCount++;
        //         rc.setIndicatorString("Updated broadcast data");
        //     }

        //     String s = "senseBroadcastFlagLocations";
        //     for(MapLocation ml : data) {
        //         s += (ml == null ? "NULL" : ml.toString());
        //     }
        //     rc.setIndicatorString(s);
        // }

        

        MapLocation [] data = rc.senseBroadcastFlagLocations(); // This method is hard to use.  The length of the returned array is not fixed.
        for(MapLocation ml : data) {
            MyBroadcastLocationData el = myBroadcastMap.get(ml);
            if(el == null) {
                myBroadcastMap.put(ml, new MyBroadcastLocationData());
            }
        }

        MapLocation nearestLoc = getNearestNonEliminatedEnemyFlagLocationMayBeNull(rc);
        if(nearestLoc != null
            && rc.getLocation().distanceSquaredTo(nearestLoc) <= 6
        ) {
            boolean canSenseEnemyFlag = false;
            for(FlagInfo fi : sensedFlags) {
                if(rc.getTeam().opponent().equals(fi.getTeam())) {
                    canSenseEnemyFlag = true;
                }
            }
            if(!canSenseEnemyFlag) {
                MyBroadcastLocationData el = myBroadcastMap.get(nearestLoc);
                el.isEliminated = true;
                myBroadcastMap.put(nearestLoc, el);
            }
        }
    }
    // static MapLocation[] getApproximateEnemyFlagLocationsMayBeEmpty(RobotController rc) {
    //     if(broadcastLocationCount >= 1) {
    //         MapLocation [] result = new MapLocation[broadcastLocationTotals.length];
    //         for(int k = 0; k < broadcastLocationTotals.length; k++) {
    //             result[k] = new MapLocation(
    //                 broadcastLocationTotals[k][0] / broadcastLocationCount,
    //                 broadcastLocationTotals[k][1] / broadcastLocationCount
    //             );
    //         }
    //         return result;
    //     } else {
    //         return new MapLocation[0];
    //     }
    // }
    // static MapLocation getNearestApproximateEnemyFlagLocationMayBeNull(RobotController rc) {
    //     int minDistSqd = 0; MapLocation bestLoc = null;
    //     for(MapLocation ml : getApproximateEnemyFlagLocationsMayBeEmpty(rc)) {
    //         final int dist = rc.getLocation().distanceSquaredTo(ml);
    //         if(bestLoc == null || dist < minDistSqd) {
    //             bestLoc = ml; minDistSqd = dist;
    //         }
    //     }
    //     return bestLoc;
    // }
    static MapLocation getNearestNonEliminatedEnemyFlagLocationMayBeNull(RobotController rc) {
        int minDistSqd = 12345;
        MapLocation nearestLoc = null;
        for(Map.Entry<MapLocation, MyBroadcastLocationData> e : myBroadcastMap.entrySet()) {
            if(e.getValue().isEliminated == false) {
                final int distSqd = rc.getLocation().distanceSquaredTo(e.getKey());
                if(distSqd < minDistSqd) {
                    nearestLoc = e.getKey();
                    minDistSqd = distSqd;
                }
            }
        }
        return nearestLoc;
    }

    static void callForHelpIfEnemiesApproachFlag(RobotController rc) throws GameActionException {
        for(FlagInfo fi : sensedFlags) {
            if(fi.isPickedUp()
                && rc.getTeam().equals(fi.getTeam())
                && rc.getRoundNum() > GameConstants.SETUP_ROUNDS
            ) {
                callForAssistance(rc, fi.getLocation());
            }

            // I tried removing this.  The difference was small, but it lost about 1 more match than it won, over many matches.
            if(rc.getLocation().equals(fi.getLocation())
                && nearestEnemyRobot != null
                && rc.getLocation().distanceSquaredTo(nearestEnemyRobot.getLocation()) <= 8
            ) {
                callForAssistance(rc, rc.getLocation());
            }
        }
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
        if(rc.isMovementReady()) {
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

}
