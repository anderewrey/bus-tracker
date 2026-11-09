# SPDX-FileCopyrightText: 2026 anderewrey
# SPDX-License-Identifier: GPL-3.0-or-later
#
# R8 rules for the release build. The app uses no reflection of its own; the libraries bring
# their own rules. Keep the names, so logs (e.javaClass.simpleName) and crash traces stay
# readable: the code is open source anyway.
-dontobfuscate
