This directory contains customizations to define and apply OEM Design Token to both the system and apps that reference the tokens.

### OEMDesignTokenBase
Android library that is a singular source of truth for the definition of token values. Set the values of color, text and shape tokens as resources in this library, which will be a dependency for RROs applying OEM Design Token values across the system.

### OEMDesignTokenFrameworkResRRO
This RRO applies the token values defined in `OEMDesignTokenBase` to `framework-res` by overlaying framework resources (themes, textAppearances, colors etc.).

### OEMDesignTokenRRO
This RRO applies the token values defined in `OEMDesignTokenBase` to the OEM Design Token Shared Library installed on the system. Apps that directly reference token values will resolve values defined in this RRO.

### OEMDesignTokenCarUiPluginRRO
This RRO overlays the resources of the Car UI Library plugin to reference OEM Design Token values where appropriate.

```
                           +-----------------------------+            +-----------------+
                           |                             |            |                 |
                           |                             |            |                 |
                           |                             | Customizes |                 |
                           | Existing framework-res RROs +------------>  framework-res  |
                           |                             |            |                 |
                           |                             |            |                 |
                           |                             |            |                 |
                           +-----------^-----------------+            +-----------------+
                                       |
+----------------------+               |
|                      |               |Renames resources
|                      |               |
|                      |               |
|  OEMDesignTokenBase  +---------------+
|                      |               |
|                      |               |
|                      |               |Sets values
+----------------------+               |
                                       |
                         +-------------v------------+            +---------------------------------+
                         |                          |            |                                 |
                         |                          |            |                                 |
                         |                          |            |                                 |
                         |     OEMDesignTokenRRO    +------------> OEM Design Token Shared Library |
                         |                          | Customizes |                                 |
                         |                          |            |                                 |
                         |                          |            |                                 |
                         +--------------------------+            +-----------------+---------------+
                                                                                   |
                                                                                   |
                                                                                   |
                                                                                   |References tokens
                                                                                   |
                                                                                   |
                                                                                   |
                                                                   +---------------v--------------+            +-----------------------+
                                                                   |                              |            |                       |
                                                                   |                              |            |                       |
                                                                   |                              |            |                       |
                                                                   | OEMDesignTokenCarUiPluginRRO +------------>  Car UI Proxy Plugin  |
                                                                   |                              | Customizes |                       |
                                                                   |                              |            |                       |
                                                                   |                              |            |                       |
                                                                   +------------------------------+            +-----------------------+
```
