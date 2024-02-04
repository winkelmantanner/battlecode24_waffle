This is my bot for Battlecode 2024, my fifth year doing Battlecode.  As in 2022 and 2023, I was only eligible for the two sprint tournaments.  But unlike those two years, this year I took time off from work to do Battlecode.  That means this bot had more effort put into it than the previous two years.  I didn't even post last year's bot on GitHub because it was very weak.

This year's game gets a 5-star rating from me.  It was a capture-the-flag game, where victory is achieved by capturing the flags of the opponent.

So what does this bot do?
Probably the most unusual thing about this bot is that when it has the flag, it retraces its own footsteps to get to wherever it spawned.  This had pros and cons.  The pro was that it never gets stuck in corners, and it tends to avoid the enemy.  The con is that it doesn't go for the nearest friendly spawn zone.  Also, I tried making it go for a friendly spawn zone if it sees that it can reach it, but this made it perform worse against the previous version of itself.

