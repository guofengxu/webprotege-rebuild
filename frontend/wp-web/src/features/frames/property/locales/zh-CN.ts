const zhCN = {
  framesProperty: {
    annotations: "注解",
    parents: "父属性",
    domains: "定义域",
    ranges: "值域",
    characteristics: "特征",
    inverses: "逆属性",
    otherAxioms: "其它公理",
    functional: "函数型",
    functionalHint: "每个主体最多有一个该属性的值。",
    loading: "加载中…",
    saveSuccess: "已保存为修订 {revision}",
    iriPlaceholder: "输入绝对 IRI 后回车添加",
    iriInvalid: "非法 IRI：须为绝对 IRI（含协议）",
    iriRemove: "移除 {iri}",
    iriEmpty: "尚未添加 IRI",
    characteristic: {
      FUNCTIONAL: "函数型（Functional）— 每个主体最多一个值",
      INVERSE_FUNCTIONAL: "逆函数型（Inverse functional）— 每个值最多一个主体",
      TRANSITIVE: "传递（Transitive）— 若 aRb 且 bRc 则 aRc",
      SYMMETRIC: "对称（Symmetric）— 若 aRb 则 bRa",
      ASYMMETRIC: "反对称（Asymmetric）— 若 aRb 则不能 bRa",
      REFLEXIVE: "自反（Reflexive）— 每个个体到自身都有此关系",
      IRREFLEXIVE: "反自反（Irreflexive）— 没有个体到自身有此关系",
    },
  },
};

export default zhCN;
