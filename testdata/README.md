# 测试语料

本目录的文件供内核测试和后续导入测试使用。内容全部是为本仓库手写的，或由 OWL API 5.5.1 从手写的 `warehouse-sample.ttl` 保存出来。没有第三方本体，也没有外部版权材料。

`SyntheticOntologyGenerator` 不在这里落盘。它在 `backend/wp-kernel` 的测试源码里，按类数量、平均分支数、每类标签语言数、每类注解断言数、个体数和随机种子生成本体。相同参数和种子得到同一组公理。平均分支数是树的分支因子：打乱类顺序后，除根以外每个类的父类下标是 `(序号 - 1) / 平均分支数`；分支数为 0 时不生成子类公理。

## ontologies/axioms-all-types.ofn

手写的 OWL 函数式语法。本体 IRI 是 `http://example.org/wp-test/axioms-all-types`。覆盖 OWL API 5.5.1 `AxiomType.AXIOM_TYPES` 中的每一种公理，并包含 6 种实体声明（类、对象属性、数据属性、注解属性、命名个体、数据类型）、带 `ObjectIntersectionOf` 的等价类、属性链、7 种对象属性特征、中英文注解、匿名个体和一条 SWRL 规则。

OWL 2 没有注解属性的等价公理和不相交公理，因此这两种关系只写在对象属性和数据属性上。

## ontologies/labels-multilingual.ttl

手写的 Turtle。20 个类，IRI 为 `http://example.org/wp-test/labels#C01` 到 `C20`。

- `C01`–`C10`：`rdfs:label` 与 `skos:prefLabel` 都有 zh、en。
- `C11`–`C14`：只有 `rdfs:label` 的 zh、en。
- `C15`–`C16`：只有 `skos:prefLabel` 的 zh、en。
- `C17`：只有 `rdfs:label` 的 zh。`C18`：只有 `rdfs:label` 的 en。
- `C19`：只有 `skos:prefLabel` 的 zh。`C20`：只有 `skos:prefLabel` 的 en。

## ontologies/imports/

手写的 RDF/XML。`main.owl` 的本体 IRI 是 `http://example.org/wp-test/imports/main`，导入 `http://example.org/wp-test/imports/a` 和 `http://example.org/wp-test/imports/b`。三个文件都在这个目录里，解析时只映射到本地文件，不访问网络。

## ontologies/warehouse-sample.ttl

手写的仓库领域 Turtle，本体 IRI 是 `http://example.org/wp-test/warehouse`。30 个类，包括托盘、货位、巷道、堆垛机、AGV 等，每个类都有中文和英文 `rdfs:label`。类层级包括货位、月台、设备、搬运单元、作业任务和工作站。按本体 IRI 导入时解析到这个文件；`formats/` 里的副本不参与导入映射。

## ontologies/formats/

同一份 `warehouse-sample.ttl` 用 OWL API 5.5.1 另存的五种序列化，公理集合与源文件一致：

| 文件 | 格式 |
|---|---|
| `warehouse-sample.owl` | RDF/XML |
| `warehouse-sample.ttl` | Turtle |
| `warehouse-sample.owx` | OWL/XML |
| `warehouse-sample.ofn` | OWL 函数式语法 |
| `warehouse-sample.omn` | Manchester 语法 |

修改 `warehouse-sample.ttl` 之后要重新保存这五个文件。Manchester 语法重新解析时会补上 `rdfs:label`、`rdfs:comment`、`rdf:langString`、`xsd:string` 的声明；比较公理时忽略 RDF、RDFS、OWL、XSD 保留词汇上的声明。不要把 zip 放进本目录；需要 zip 的测试在运行时自己打包。
