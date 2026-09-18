package de.erethon.mccinema.commands;

final class CommandInput {

    private CommandInput() {
    }

    static String[] arguments(String subcommand, String... values) {
        String[] arguments = new String[values.length + 1];
        arguments[0] = subcommand;
        System.arraycopy(values, 0, arguments, 1, values.length);
        return arguments;
    }
}
