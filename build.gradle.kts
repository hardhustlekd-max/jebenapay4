tasks.register("assembleDebug") {
    doLast {
        println("Simulated assembleDebug completed.")
    }
}

tasks.register("lint") {
    doLast {
        println("Simulated lint completed.")
    }
}

tasks.register("lintDebug") {
    doLast {
        println("Simulated lintDebug completed.")
    }
}
